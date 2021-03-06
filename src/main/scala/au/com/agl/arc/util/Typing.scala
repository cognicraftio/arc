package au.com.agl.arc.util

import java.sql.Date
import java.sql.Timestamp
import java.text.DecimalFormat
import java.text.ParsePosition
import java.time.LocalDate
import java.time.LocalTime
import java.time.{ZoneId, ZonedDateTime}
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField

import org.apache.commons.codec.binary.Base64

import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.util.LongAccumulator

import au.com.agl.arc.api._

object Typing {

  import API._

  /** Phantom Type to enable compiler to find the encoder we want
    */
  type TypedRow = Row

  /** Uses the provided schema to perform a DataFrame map from Row to a TypedRow.
    * We must use the DataFrame map and not RDD as RDD operations break the 
    * logical plan which is required for lineage.
    */
  private def performTyping(df: DataFrame, cols: List[ExtractColumn], typedSchema: StructType, failMode: FailModeType, valueAccumulator: LongAccumulator, errorAccumulator: LongAccumulator)( implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Dataset[TypedRow] = {
    val incomingSchema = df.schema.zipWithIndex

    /** Create a dynamic RowEncoder from the provided schema. We use the phantom
      * TypeRow type to enable implicit resolution to find our encoder.
      */
    implicit val typedEncoder: Encoder[TypedRow] = org.apache.spark.sql.catalyst.encoders.RowEncoder(typedSchema)

    df.map[TypedRow] { row: Row =>
        // type each column in the row accordind to the schema, we accumulate errors to append to a separate column
        val (values, errors) = incomingSchema.foldLeft[(List[Any], List[TypingError])]((Nil, Nil)){ case ((valuesAccum, errorsAccum), (field, fieldIdx)) =>
          val fieldMetadata = field.metadata
          
          // If field is flagged as internal pass through directly as it will not be in the incoming metadata          
          if (fieldMetadata.contains("internal") && fieldMetadata.getBoolean("internal") == true) {
              (row.get(fieldIdx) :: valuesAccum, errorsAccum)
          } else {
            val col = cols(fieldIdx)
            // Pass through when the incoming type matches the outgoing type
            // except where StringType so that rules like nullableValues can be applied consistently
            if (col.sparkDataType == field.dataType && field.dataType != StringType) {
                (row.get(fieldIdx) :: valuesAccum, errorsAccum)
            } else {              
              // TODO: add support for converting between types etc decimal to timestamp, date to timestamp etc
              Typing.typeValue(row.getString(fieldIdx), col) match {
                case (Some(v), Some(err)) => (v :: valuesAccum, err :: errorsAccum)
                case (Some(v), None) => (v :: valuesAccum, errorsAccum)
                case (None, Some(err)) => (null :: valuesAccum, err :: errorsAccum)
                case (None, None) => (null :: valuesAccum, errorsAccum)
              }
            }
          }
        }

        val allErrors: List[Row] = errors match {
          case Nil => Nil
          case _ =>
            errors.reverse.map { err =>
              Row(err.field, err.message)
            }
        }

        if (failMode == FailModeTypeFailFast && allErrors.length != 0) {
          throw new Exception(s"""TypingTransform with failMode equal to '${failMode.sparkString}' cannot continue due to row with error(s): [${allErrors.map(_.toString).mkString(", ")}].""")
        }        

        // TODO: added idx column back (if not in the extract)
        //val rowValues = allErrors :: idx :: values
        val rowValues = allErrors :: values

        // record metrics
        errorAccumulator.add(errors.length)
        valueAccumulator.add(values.length)

        // cast to a TypedRow to fit the Dataset map method requirements
        Row(rowValues.reverse:_*).asInstanceOf[TypedRow]
    }
  }

  def typeDataFrame(untypedDataframe: DataFrame, cols: List[ExtractColumn], failMode: FailModeType, valueAccumulator: LongAccumulator, errorAccumulator: LongAccumulator)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): DataFrame = {
    val schema = Extract.toStructType(cols)
    val internalFields = untypedDataframe.schema.filter(field => { field.metadata.contains("internal") && field.metadata.getBoolean("internal") == true }).toList
    
    val typedSchema = StructType(
      schema.fields.toList ::: internalFields ::: Typing.typedFields
    )

    // applies data types but not metadata
    val typedDS = performTyping(untypedDataframe, cols, typedSchema, failMode, valueAccumulator, errorAccumulator)

    // re-attach metadata to result
    var typedDF = typedDS.toDF
    typedSchema.foreach(field => {
      typedDF = typedDF.withColumn(field.name, col(field.name).as(field.name, field.metadata))
    })

    typedDF  
  }

  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | value.isNull | value.isAllowedNullValue | col.nullReplacementValue | col.nullable | Result                      |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | true         | false                    | false                    | true         | return null                 |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | true         | false                    | false                    | false        | exception                   |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | true         | false                    | true                     | true         | return nullReplacementValue |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | true         | false                    | true                     | false        | return nullReplacementValue |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | false        | true                     | false                    | true         | return null                 |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | false        | true                     | false                    | false        | exception                   |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | false        | true                     | true                     | true         | return nullReplacementValue |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  // | false        | true                     | true                     | false        | return nullReplacementValue |
  // +--------------+--------------------------+--------------------------+--------------+-----------------------------+
  def typeValue(value: String, col: ExtractColumn): (Option[Any], Option[TypingError]) = {
    import Typeable._

    val valueToType = if (col.trim && value != null) value.trim else value
    val isNull = valueToType == null
    val isAllowedNullValue = col.nullableValues.contains(valueToType)

    if (isNull || isAllowedNullValue) {
        col.nullReplacementValue match {
            case Some(nullReplacementValue) => {
              col match {
                case c:BinaryColumn => BinaryTypeable.typeValue(c, nullReplacementValue)
                case c:BooleanColumn => BooleanTypeable.typeValue(c, nullReplacementValue)
                case c:DateColumn => DateTypeable.typeValue(c, nullReplacementValue)
                case c:DecimalColumn => DecimalTypeable.typeValue(c, nullReplacementValue)
                case c:DoubleColumn => DoubleTypeable.typeValue(c, nullReplacementValue)
                case c:TimeColumn => TimeTypeable.typeValue(c, nullReplacementValue)
                case c:IntegerColumn => IntegerTypeable.typeValue(c, nullReplacementValue)
                case c:LongColumn => LongTypeable.typeValue(c, nullReplacementValue)
                case c:StringColumn => StringTypeable.typeValue(c, nullReplacementValue)
                case c:TimestampColumn => TimestampTypeable.typeValue(c, nullReplacementValue)
              }                
            }
            case None => {
              if (col.nullable) {
                (None, None)
              } else {
                (None, Some(TypingError.nullReplacementValueNullErrorForCol(col)))       
              }
            }
        }
    } else {
      // else take string value and try to convert to column type
      col match {
        case c:BinaryColumn => BinaryTypeable.typeValue(c, valueToType)
        case c:BooleanColumn => BooleanTypeable.typeValue(c, valueToType)
        case c:DateColumn => DateTypeable.typeValue(c, valueToType)
        case c:DecimalColumn => DecimalTypeable.typeValue(c, valueToType)
        case c:DoubleColumn => DoubleTypeable.typeValue(c, valueToType)
        case c:IntegerColumn => IntegerTypeable.typeValue(c, valueToType)
        case c:LongColumn => LongTypeable.typeValue(c, valueToType)
        case c:StringColumn => StringTypeable.typeValue(c, valueToType)
        case c:TimeColumn => TimeTypeable.typeValue(c, valueToType)
        case c:TimestampColumn => TimestampTypeable.typeValue(c, valueToType)
      }  
    }
  }

  val errorStructType: StructType =
    StructType(
      StructField("field", StringType, false) ::
      StructField("message", StringType, false) :: Nil
    )

  val typedFields: List[StructField] =
    // TODO: add _index column back
    //StructField("_index", LongType, false, new MetadataBuilder().putBoolean("internal", true).build()) ::
    StructField("_errors", ArrayType(errorStructType), true, new MetadataBuilder().putBoolean("internal", true).build()) :: Nil

  type TypingResult[S] = (Option[S], Option[TypingError])

  sealed trait Typeable[T <: ExtractColumn, S] {
    def typeValue(col: T, value: String): TypingResult[S]
  }

  object Typeable {

    object StringTypeable extends Typeable[StringColumn, String] {
      
      def typeValue(col: StringColumn, value: String): (Option[String], Option[TypingError]) = {
        val valueLength = value.length
        (col.minLength, col.maxLength) match {
          case (None, None) => {
            Option(value) -> None
          }
          case (Some(minLength), None) if (valueLength > minLength) => {
            Option(value) -> None
          }
          case (Some(minLength), None) if (valueLength < minLength) => {
            None -> Some(TypingError.forCol(col, s"""String '$value' ($valueLength characters) is less than minLength ($minLength)."""))
          }
          case (None, Some(maxLength)) if (valueLength < maxLength) => {
            Option(value) -> None
          }
          case (None, Some(maxLength)) if (valueLength > maxLength) => {
            None -> Some(TypingError.forCol(col, s"""String '$value' ($valueLength characters) is greater than maxLength ($maxLength)."""))
          }
          case (Some(minLength), Some(maxLength)) if (valueLength > minLength && valueLength < maxLength) => {
            Option(value) -> None
          }
          case (Some(minLength), Some(maxLength)) if (valueLength < minLength && valueLength < maxLength) => {
            None -> Some(TypingError.forCol(col, s"""String '$value' ($valueLength characters) is less than minLength ($minLength)."""))
          }
          case (Some(minLength), Some(maxLength)) if (valueLength > minLength && valueLength > maxLength) => {
            None -> Some(TypingError.forCol(col, s"""String '$value' ($valueLength characters) is greater than maxLength ($maxLength)."""))
          }
          case (Some(minLength), Some(maxLength)) if (valueLength < minLength && valueLength > maxLength) => {
            None -> Some(TypingError.forCol(col, s"""String '$value' ($valueLength characters) is less than minLength ($minLength) and is greater than maxLength ($maxLength)."""))
          }  
        }
      }

    }

    object NumberUtils {

      // memoizedFormatters not used as DecimalFormat objects are not thread safe and performance
      // cost of using them is not significant enough. Could be opportunity for optimisation if care is taken.

      @scala.annotation.tailrec
      def parseNumber(formatters: List[String], value: String): Option[Number] = {
        formatters match {
          case Nil => None
          case head :: tail =>
            try {
              val formatter = new DecimalFormat(head)
              val pos = new ParsePosition(0)
              val number = formatter.parse(value, pos)

              // ensure all characters from input string have been processed  
              // and no errors exist
              if (pos.getIndex != value.length || pos.getErrorIndex != -1) {
                throw new Exception()
              }

              Option(number)
            } catch {
              case e: Exception =>
                // Log Error and occurances?
                parseNumber(tail, value)
            }
        }
      }   

      @scala.annotation.tailrec
      def parseBigDecimal(formatters: List[String], value: String): Option[BigDecimal] = {
        formatters match {
          case Nil => None
          case head :: tail =>
            try {
              // get the formatter from memory if available
              val formatter = new DecimalFormat(head)
              formatter.setParseBigDecimal(true)
              val pos = new ParsePosition(0)
              val number = formatter.parse(value, pos).asInstanceOf[java.math.BigDecimal]

              // ensure all characters from input string have been processed  
              // and no errors exist
              if (pos.getIndex != value.length || pos.getErrorIndex != -1) {
                throw new Exception
              }

              Option(scala.math.BigDecimal(number))
            } catch {
              case e: Exception =>
                // Log Error and occurances?
                parseBigDecimal(tail, value)
            }
        }
      }  

    }    

    object IntegerTypeable extends Typeable[IntegerColumn, Int] {
      import NumberUtils._

      def typeValue(col: IntegerColumn, value: String): (Option[Int], Option[TypingError]) = {
        val formatters = col.formatters.getOrElse(List("#,##0;-#,##0"))
        
        try {
          val v = col.formatters match {
            case Some(fmt) => {
              // number.intValue does not throw exception when < Int.MinValue || > Int.MaxValue
              val number = parseNumber(fmt, value)
              number.map( num => num.toString.toInt )
            } 
            case None => Option(value.toInt)
          }
          if(v == None)        
            throw new Exception()        
          v -> None                   
        } catch {
          case e: Exception =>
            None -> Some(TypingError.forCol(col, s"""Unable to convert '${value}' to integer using formatters [${formatters.map(c => s"'${c}'").mkString(", ")}]"""))
        }
      }

    }

    object LongTypeable extends Typeable[LongColumn, Long] {
      import NumberUtils._

      def typeValue(col: LongColumn, value: String): (Option[Long], Option[TypingError]) = {
        val formatters = col.formatters.getOrElse(List("#,##0;-#,##0"))

        try {
          val v = col.formatters match {
            case Some(fmt) => {
              // number.longValue does not throw exception when < Long.MinValue || >  Long.MaxValue
              val number = parseNumber(fmt, value)
              number.map( num => num.toString.toLong )
            } 
            case None => Option(value.toLong)
          }
          if(v == None)        
            throw new Exception()        
          v -> None                   
        } catch {
          case e: Exception =>
            None -> Some(TypingError.forCol(col, s"""Unable to convert '${value}' to long using formatters [${formatters.map(c => s"'${c}'").mkString(", ")}]"""))
        }
      }

    }    

    object DoubleTypeable extends Typeable[DoubleColumn, Double] {
      import NumberUtils._

      def typeValue(col: DoubleColumn, value: String): (Option[Double], Option[TypingError]) = {
        val formatters = col.formatters.getOrElse(List("#,##0.###;-#,##0.###"))

        try {
          val v = col.formatters match {
            case Some(fmt) => {
              // number.doubleValue does not throw exception when < Double.MinValue || >  Double.MaxValue
              val number = parseNumber(fmt, value)
              number.map( num => num.toString.toDouble )
            } 
            case None => Option(value.toDouble)
          }
          if(v == None)        
            throw new Exception()        
          if (v.get.isInfinite)
            throw new Exception()            
          v -> None                   
        } catch {
          case e: Exception =>
            None -> Some(TypingError.forCol(col, s"""Unable to convert '${value}' to double using formatters [${formatters.map(c => s"'${c}'").mkString(", ")}]"""))
        }
      }
      
    }

    object DecimalTypeable extends Typeable[DecimalColumn, Decimal] {
      import NumberUtils._

      def typeValue(col: DecimalColumn, value: String): (Option[Decimal], Option[TypingError]) = {
        val formatters = col.formatters.getOrElse(List("#,##0.###;-#,##0.###"))
        
        try {
          val v = col.formatters match {
            case Some(fmt) => {
              val number = parseBigDecimal(fmt, value)
              number.map( num => Decimal(num, col.precision, col.scale) )
            } 
            case None => {
              val number = Decimal(value)
              if (!number.changePrecision(col.precision, col.scale)) {
                throw new Exception()
              }
              Option(number)
            }
          }            
          if(v == None)     
            throw new Exception()        
          v -> None                   
        } catch {
          case e: Exception =>
            None -> Some(TypingError.forCol(col, s"""Unable to convert '${value}' to decimal(${col.precision}, ${col.scale}) using formatters [${formatters.map(c => s"'${c}'").mkString(", ")}]"""))
        }
      }
      
    }

    object BinaryTypeable extends Typeable[BinaryColumn, Array[Byte]] {
      def typeValue(col: BinaryColumn, value: String): (Option[Array[Byte]], Option[TypingError]) = {
          binaryOrError(col, value)
      }

      def binaryOrError(col: BinaryColumn, value: String): TypingResult[Array[Byte]] = {
        try {
          col.encoding match {
            case EncodingTypeBase64 => {
              val valueByteArray = value.getBytes
              if (Base64.isBase64(valueByteArray)) {
                Option(Base64.decodeBase64(value)) -> None
              } else {
                throw new Exception()        
              }
            }
            case EncodingTypeHexadecimal => {
              // will throw exception if not valid hexadecimal
              java.lang.Long.parseLong(value, 16)
              Option(org.apache.spark.sql.catalyst.expressions.Hex.unhex(value.getBytes)) -> None
            }
          }
        } catch {
          case e: Exception =>
            None -> Some(TypingError.forCol(col, s"Unable to convert '${value}' to binary using '${col.encoding.sparkString}' decoding."))
        }
      }

    }    

    object BooleanTypeable extends Typeable[BooleanColumn, Boolean] {
      def typeValue(col: BooleanColumn, value: String): (Option[Boolean], Option[TypingError]) = {
          booleanOrError(col, value)
      }

      def booleanOrError(col: BooleanColumn, value: String): TypingResult[Boolean] = {
        try {
          if (col.trueValues.contains(value)) {
            Option(true) -> None
          } else if (col.falseValues.contains(value)) {
            Option(false) -> None
          } else {
            None -> Some(TypingError.forCol(col, s"""Unable to convert '${value}' to boolean using provided true values: [${col.trueValues.map(c => s"'${c}'").mkString(", ")}] or false values: [${col.falseValues.map(c => s"'${c}'").mkString(", ")}]"""))
          }
        } catch {
          case e: Exception =>
            None -> Some(TypingError.forCol(col, s"Unable to convert '${value}' to boolean"))
        }
      }

    }

    object DateTimeUtils {
      private val memoizedFormatters: collection.mutable.Map[String, DateTimeFormatter] = {
        val dtf = new collection.mutable.HashMap[String, DateTimeFormatter]()

        val epochFormatter = new DateTimeFormatterBuilder()
          .appendValue(ChronoField.INSTANT_SECONDS, 10, 10, SignStyle.NEVER)
          .toFormatter()
          .withZone(ZoneId.of("UTC"))

        val epochMillisFormatter = new DateTimeFormatterBuilder()
          .appendValue(ChronoField.INSTANT_SECONDS, 10, 10, SignStyle.NEVER)
          .appendValue(ChronoField.MILLI_OF_SECOND, 3)
          .toFormatter()
          .withZone(ZoneId.of("UTC"))          

        dtf.put("ssssssssss:UTC:false", epochFormatter)
        dtf.put("sssssssssssss:UTC:false", epochMillisFormatter)
        dtf
      }

      private def zonedDateTimeFormatter(pattern: String, tz: ZoneId, strict: Boolean): DateTimeFormatter = {
        val key = s"${pattern}:${tz.getId}:${strict}"
        // get the existing formatter or add it to memory
        memoizedFormatters.get(key).getOrElse {
          val formatter = strict match {
            case true => DateTimeFormatter.ofPattern(pattern).withZone(tz).withResolverStyle(ResolverStyle.STRICT)
            case false => DateTimeFormatter.ofPattern(pattern).withZone(tz).withResolverStyle(ResolverStyle.SMART) // smart is default
          }
          memoizedFormatters.put(key, formatter)
          formatter
        }
      }

      private def dateTimeFormatter(pattern: String, strict: Boolean): DateTimeFormatter = {
        val key = s"${pattern}:${strict}"
        // get the existing formatter or add it to memory
        memoizedFormatters.get(key).getOrElse {
          val formatter = strict match {
            case true => DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)
            case false => DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.SMART) // smart is default
          }
          memoizedFormatters.put(key, formatter)
          formatter
        }
      }          

      @scala.annotation.tailrec
      def parseDateTime(formatters: List[String], tz: ZoneId, strict: Boolean, value: String): Option[ZonedDateTime] = {
        formatters match {
          case Nil => None
          case head :: tail =>
            try {
              // get the formatter from memory if available
              val fmt = zonedDateTimeFormatter(head, tz, strict) 
              Option(ZonedDateTime.parse(value, fmt))
            } catch {
              case e: Exception =>
                // Log Error and occurances?
                parseDateTime(tail, tz, strict, value)
            }
        }
      }

      @scala.annotation.tailrec
      def parseDate(formatters: List[String], strict: Boolean, value: String): Option[LocalDate] = {
        formatters match {
          case Nil => None
          case head :: tail =>
            try {
              // get the formatter from memory if available
              val fmt = dateTimeFormatter(head, strict)               
              Option(LocalDate.parse(value, fmt))
            } catch {
              case e: Exception =>
                // Log Error and occurances?
                parseDate(tail, strict, value)
            }
        }
      }

      @scala.annotation.tailrec
      def parseTime(formatters: List[String], value: String): Option[LocalTime] = {
        formatters match {
          case Nil => None
          case head :: tail =>
            try {
              // get the formatter from memory if available
              val fmt = dateTimeFormatter(head, false)
              Option(LocalTime.parse(value, fmt))
            } catch {
              case e: Exception =>
                // Log Error and occurances?
                parseTime(tail, value)
            }
        }
      }      
    }

    object TimestampTypeable extends Typeable[TimestampColumn, Timestamp] {
      import DateTimeUtils._

      def timestampOrError(col: TimestampColumn, value: String): TypingResult[Timestamp] = {
        val tz = ZoneId.of(col.timezoneId)
        val dt = col.time match {
          case Some(time) => { 
            val date = parseDate(col.formatters, col.strict, value)
            date.map( _dt => _dt.atStartOfDay(tz).withHour(time.getHour).withMinute(time.getMinute).withSecond(time.getSecond).withNano(time.getNano))
          }
          case None => parseDateTime(col.formatters, tz, col.strict, value)
        }
       
        val v = dt.map( _dt => Timestamp.from(_dt.toInstant()))
        if(v == None)        
          throw new Exception()  
        v -> None
      }

      def typeValue(col: TimestampColumn, value: String): (Option[Timestamp], Option[TypingError]) = {
          try {
            timestampOrError(col, value)
          } catch {
            case e: Exception =>
              None -> Some(TypingError.forCol(col, s"""Unable to convert '$value' to timestamp using formatters [${col.formatters.map(c => s"'${c}'").mkString(", ")}] and timezone '${col.timezoneId}'"""))
          }
      }
    }

    object DateTypeable extends Typeable[DateColumn, Date] {
      import DateTimeUtils._

      def dateOrError(col: DateColumn, value: String): TypingResult[Date] = {
        val dt = parseDate(col.formatters, col.strict, value)        
        val v = dt.map( _dt => java.sql.Date.valueOf(_dt))
        if(v == None)        
          throw new Exception()        
        v -> None
      }

      def typeValue(col: DateColumn, value: String): (Option[Date], Option[TypingError]) = {
          try {
            dateOrError(col, value)
          } catch {
            case e: Exception =>
              None -> Some(TypingError.forCol(col, s"""Unable to convert '$value' to date using formatters [${col.formatters.map(c => s"'${c}'").mkString(", ")}]"""))
          }
      }
    }

    object TimeTypeable extends Typeable[TimeColumn, String] {
      import DateTimeUtils._

      def timeOrError(col: TimeColumn, value: String): TypingResult[String] = {
        val tm = parseTime(col.formatters, value)        
        val v = tm.map( _tm => _tm.format(DateTimeFormatter.ISO_LOCAL_TIME))
        if(v == None)        
          throw new Exception()        
        v -> None
      }

      def typeValue(col: TimeColumn, value: String): (Option[String], Option[TypingError]) = {
          try {
            timeOrError(col, value)
          } catch {
            case e: Exception =>
              None -> Some(TypingError.forCol(col, s"""Unable to convert '$value' to time using formatters [${col.formatters.map(c => s"'${c}'").mkString(", ")}]"""))
          }
      }
    } 

  }
}

