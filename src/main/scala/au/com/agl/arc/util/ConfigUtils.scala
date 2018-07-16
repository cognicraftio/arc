package au.com.agl.arc.util

import java.net.URI
import java.io.File
import java.io.FileNotFoundException
import java.lang.IllegalArgumentException
import java.sql.DriverManager

import scala.collection.JavaConverters._
import scala.util.Properties._

import com.typesafe.config._

import org.apache.spark.sql._
import org.apache.spark.ml.PipelineModel
import org.apache.spark.SparkFiles

import au.com.agl.arc.api._
import au.com.agl.arc.api.API._

import au.com.agl.arc.util.EitherUtils._
import au.com.agl.arc.util.JDBCUtils._

object ConfigUtils {

  def paramsToOptions(params: Map[String, String], options: Seq[String]): Map[String, String] = {
      params.filter{ case (k,v) => options.contains(k) }
  }

  def parsePipeline()(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, argsMap: collection.mutable.Map[String, String], env: String): Either[List[Error], ETLPipeline] = {
    val configUri: Option[String] = argsMap.get("etl.config.uri").orElse(envOrNone("ETL_CONF_URI"))    

    configUri match {
      case Some(uri) => parseConfig(new URI(uri))
      case None => Left(ConfigError("file", s"No config defined as a command line argument --etl.config.uri or ETL_CONF_URI environment variable.") :: Nil)
     }
  }

  def parseConfig(uri: URI)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, argsMap: collection.mutable.Map[String, String], env: String): Either[List[Error], ETLPipeline] = {
    val base = ConfigFactory.load()    

    uri.getScheme match {
      case "local" => {
        val filePath = SparkFiles.get(uri.getPath)
        val etlConfString = CloudUtils.getTextBlob(filePath)
        val etlConf = ConfigFactory.parseString(etlConfString, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
        val c = etlConf.withFallback(base).resolve()
        readPipeline(c, uri)
      }      
      case "file" => {
        val etlConfString = CloudUtils.getTextBlob(uri.toString)
        val etlConf = ConfigFactory.parseString(etlConfString, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
        val c = etlConf.withFallback(base).resolve()
        readPipeline(c, uri)
      }
      case "s3a" => {
        val s3aEndpoint: Option[String] = argsMap.get("etl.config.fs.s3a.endpoint").orElse(envOrNone("ETL_CONF_S3A_ENDPOINT")) 
        val s3aConnectionSSLEnabled: Option[String] = argsMap.get("etl.config.fs.s3a.connection.ssl.enabled").orElse(envOrNone("ETL_CONF_S3A_CONNECTION_SSL_ENABLED")) 
        val s3aAccessKey: Option[String] = argsMap.get("etl.config.fs.s3a.access.key").orElse(envOrNone("ETL_CONF_S3A_ACCESS_KEY")) 
        val s3aSecretKey: Option[String] = argsMap.get("etl.config.fs.s3a.secret.key").orElse(envOrNone("ETL_CONF_S3A_SECRET_KEY")) 

        val endpoint = s3aEndpoint match {
          case Some(value) => value
          case None => throw new IllegalArgumentException(s"AWS Endpoint not provided to for: ${uri}. Set etl.config.fs.s3a.endpoint property or ETL_CONF_S3A_ENDPOINT environment variable.")
        }
        val connectionSSLEnabled = s3aConnectionSSLEnabled match {
          case Some(value) => value
          case None => throw new IllegalArgumentException(s"AWS Connection SSL Enabled not provided for: ${uri}. Set etl.config.fs.s3a.connection.ssl.enabled property or ETL_CONF_S3A_CONNECTION_SSL_ENABLED environment variable.")
        }        
        val accessKey = s3aAccessKey match {
          case Some(value) => value
          case None => throw new IllegalArgumentException(s"AWS Access Key not provided for: ${uri}. Set etl.config.fs.s3a.access.key property or ETL_CONF_S3A_ACCESS_KEY environment variable.")
        }
        val secretKey = s3aSecretKey match {
          case Some(value) => value
          case None => throw new IllegalArgumentException(s"AWS Secret Key not provided for: ${uri}. Set etl.config.fs.s3a.secret.key property or ETL_CONF_S3A_SECRET_KEY environment variable.")
        }    

        val config = Map("fs_s3a_endpoint" -> endpoint, "fs_s3a_connection_ssl_enabled" -> connectionSSLEnabled, "fs_s3a_access_key" -> accessKey, "fs_s3a_secret_key" -> secretKey)

        CloudUtils.setHadoopConfiguration(Some(Authentication.AmazonAccessKey(accessKey, secretKey)))
        val etlConfString = CloudUtils.getTextBlob(uri.toString)
        val etlConf = ConfigFactory.parseString(etlConfString, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
        val c = etlConf.withFallback(base).resolve()
        readPipeline(c, uri)        
      }
      case "wasbs" => {       
        val azureAccountName: Option[String] = argsMap.get("etl.config.fs.azure.account.name").orElse(envOrNone("ETL_CONF_AZURE_ACCOUNT_NAME")) 
        val azureAccountKey: Option[String] = argsMap.get("etl.config.fs.azure.account.key").orElse(envOrNone("ETL_CONF_AZURE_ACCOUNT_KEY")) 
        
        val accountName = azureAccountName match {
          case Some(value) => value
          case None => throw new IllegalArgumentException(s"Azure Account Name not provided for: ${uri}. Set etl.config.fs.azure.account.nameproperty or ETL_CONF_AZURE_ACCOUNT_NAME environment variable.")
        }
        val accountKey = azureAccountKey match {
          case Some(value) => value
          case None => throw new IllegalArgumentException(s"Azure Account Key not provided for: ${uri}. Set etl.config.fs.azure.account.key property or ETL_CONF_AZURE_ACCOUNT_KEY environment variable.")
        }        

        CloudUtils.setHadoopConfiguration(Some(Authentication.AzureSharedKey(accountName, accountKey)))
        val etlConfString = CloudUtils.getTextBlob(uri.toString)
        val etlConf = ConfigFactory.parseString(etlConfString, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
        val c = etlConf.withFallback(base).resolve()
        readPipeline(c, uri)
      }
      case _ => {
        Left(ConfigError("file", "make sure url scheme is defined e.g. file://${pwd}") :: Nil)
      }
    }
  }

  def readMap(path: String, c: Config): Map[String, String] = {
    if (c.hasPath(path)) {
      val params = c.getConfig(path).entrySet
      (for (e <- params.asScala) yield {
        val k = e.getKey
        val v = e.getValue
        val pv = v.unwrapped match {
          case s:String => s
          case _ => v.toString
        }
        k -> pv
      }).toMap
    } else {
      Map.empty
    }
  }

  def readAuthentication(path: String)(implicit c: Config): Either[Errors, Option[Authentication]] = {
  
    def err(msg: String): Either[Errors, Option[Authentication]] = Left(ConfigError(path, msg) :: Nil)

    try {
      if (c.hasPath(path)) {
        val authentication = readMap("authentication", c)
        if (authentication.isEmpty) {
          Right(None)
        } else {
          authentication.get("method") match {
            case Some("AzureSharedKey") => {
              val accountName = authentication.get("accountName") match {
                case Some(v) => v
                case None => throw new Exception(s"Authentication method 'AzureSharedKey' requires 'accountName' parameter.")
              } 
              val signature = authentication.get("signature") match {
                case Some(v) => v
                case None => throw new Exception(s"Authentication method 'AzureSharedKey' requires 'signature' parameter.")
              } 
              Right(Some(Authentication.AzureSharedKey(accountName, signature)))
            }
            case Some("AzureSharedAccessSignature") => {
              val accountName = authentication.get("accountName") match {
                case Some(v) => v
                case None => throw new Exception(s"Authentication method 'AzureSharedAccessSignature' requires 'accountName' parameter.")
              } 
              val container = authentication.get("container") match {
                case Some(v) => v
                case None => throw new Exception(s"Authentication method 'AzureSharedAccessSignature' requires 'container' parameter.")
              } 
              val token = authentication.get("token") match {
                case Some(v) => v
                case None => throw new Exception(s"Authentication method 'AzureSharedAccessSignature' requires 'container' parameter.")
              }                 
              Right(Some(Authentication.AzureSharedAccessSignature(accountName, container, token)))
            } 
            case Some("AmazonAccessKey") => {
              val accessKeyID = authentication.get("accessKeyID") match {
                case Some(v) => v
                case None => throw new Exception(s"Authentication method 'AmazonAccessKey' requires 'accessKeyID' parameter.")
              } 
              val secretAccessKey = authentication.get("secretAccessKey") match {
                case Some(v) => v
                case None => throw new Exception(s"Authentication method 'AmazonAccessKey' requires 'secretAccessKey' parameter.")
              }       
              Right(Some(Authentication.AmazonAccessKey(accessKeyID, secretAccessKey)))
            }                            
            case _ =>  throw new Exception(s"""Unable to parse authentication method: '${authentication.get("method").getOrElse("")}'""")
          }
        }
      } else {
        Right(None)
      }
    } catch {
      case e: Exception => err(s"Unable to read config value: ${e.getMessage}")
    }
  }

  def readSaveMode(path: String)(implicit c: Config): Either[Errors, Option[SaveMode]] = {
  
    def err(msg: String): Either[Errors, Option[SaveMode]] = Left(ConfigError(path, msg) :: Nil)

    try {
      if (c.hasPath(path)) {
        c.getString(path) match {
          case "Append" => {
            Right(Option(SaveMode.Append))
          } 
          case "ErrorIfExists" => {
            Right(Option(SaveMode.ErrorIfExists))
          }   
          case "Ignore" => {
            Right(Option(SaveMode.Ignore))
          }                    
          case "Overwrite" => {
            Right(Option(SaveMode.Overwrite))
          }     
          case _ =>  throw new Exception(s"""Unable to parse SaveMode method: '${c.getString(path)}'. Must be one of ['Append', 'ErrorIfExists', 'Ignore', 'Overwrite'].""")
        }
      } else {
        Right(None)
      }
    } catch {
      case e: Exception => err(s"Unable to read config value: ${e.getMessage}")
    }
  }

  sealed trait Error

  object Error {

    def errToString(err: Error): String = {
      err match {
        case StageError(stage, configErrors) =>
          s"""Stage '${stage}':\n${configErrors.map(e => "\t\t" + errToString(e)).mkString("\n")}"""
        case ConfigError(p, msg) => s"${p}: $msg"
      }
    }

    def pipelineErrorMsg(errors: List[Error]): String = {
      val errorMsg = errors.map(e => s"\t${ConfigUtils.Error.errToString(e)}").mkString("\n")
      s"\nETL Config contains errors:\n\n$errorMsg\n\n"
    }

  }

  case class ConfigError(path: String, message: String) extends Error

  case class StageError(stage: String, errors: List[ConfigError]) extends Error

  object ConfigError {

    def err(path: String, message: String): List[ConfigError] = ConfigError(path, message) :: Nil

  }

  type Errors =  List[ConfigError]


  trait ConfigReader[A] {

    def getValue(path: String, c: Config): Either[Errors, A]

    def getOptionalValue(path: String, c: Config): Either[Errors, Option[A]]

  }
  
  object ConfigReader {

    def getConfigValue[A](path: String, c: Config, expectedType: String)(read: => A): Either[Errors, A] = {
    
      def err(msg: String): Either[Errors, A] = Left(ConfigError(path, msg) :: Nil)

      try {
        if (c.hasPath(path)) {
          Right(read)
        } else {
          err(s"No value found for $path")
        }
      } catch {
        case wt: ConfigException.WrongType => err(s"Unable to read config value, wrong type, expected: $expectedType")
        case e: Exception => err(s"Unable to read config value: ${e.getMessage}")
      }

    }

    def getOptionalConfigValue[A](path: String, c: Config, expectedType: String)(read: => A): Either[Errors, Option[A]] = {
      if (c.hasPath(path)) {
        val value = getConfigValue(path, c, expectedType)(read)
        value match {
          case Right(cv) => Right(Option(cv))
          case Left(l) => Left(l) // matching works around typing error
        }
      } else {
        Right(None)
      }
    }

    implicit object StringConfigReader extends ConfigReader[String] {

      def getValue(path: String, c: Config): Either[Errors, String] = getConfigValue(path, c, "string"){ c.getString(path) }

      def getOptionalValue(path: String, c: Config): Either[Errors, Option[String]] = getOptionalConfigValue(path, c, "string"){ c.getString(path) }

    }

    implicit object BooleanConfigReader extends ConfigReader[Boolean] {

      def getValue(path: String, c: Config): Either[Errors, Boolean] = getConfigValue(path, c, "boolean"){ c.getBoolean(path) }

      def getOptionalValue(path: String, c: Config): Either[Errors, Option[Boolean]] = getOptionalConfigValue(path, c, "boolean"){ c.getBoolean(path) }

    }  

    implicit object IntConfigReader extends ConfigReader[Int] {

      def getValue(path: String, c: Config): Either[Errors, Int] = getConfigValue(path, c, "int"){ c.getInt(path) }

      def getOptionalValue(path: String, c: Config): Either[Errors, Option[Int]] = getOptionalConfigValue(path, c, "int"){ c.getInt(path) }

    }    

    implicit object LongConfigReader extends ConfigReader[Long] {

      def getValue(path: String, c: Config): Either[Errors, Long] = getConfigValue(path, c, "long"){ c.getLong(path) }

      def getOptionalValue(path: String, c: Config): Either[Errors, Option[Long]] = getOptionalConfigValue(path, c, "long"){ c.getLong(path) }

    }        

    def getValue[A](path: String)(implicit c: Config, reader: ConfigReader[A]): Either[Errors, A] = reader.getValue(path, c)

    def getOptionalValue[A](path: String)(implicit c: Config, reader: ConfigReader[A]): Either[Errors, Option[A]] = reader.getOptionalValue(path, c)

  }

  type StringConfigValue = Either[Errors, String]

  private def stringOrDefault(sv: StringConfigValue, default: String): String = {
    sv match {
      case Right(v) => v
      case Left(err) => default
    }
  }

  private def parseURI(path: String, uri: String): Either[Errors, URI] = {
    def err(msg: String): Either[Errors, URI] = Left(ConfigError(path, msg) :: Nil)

    try {
      // try to parse uri
      Right(new URI(uri))
    } catch {
      case e: Exception => err(s"${e.getMessage}")
    }
  }

  private def getBlob(path: String, uri: URI)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Either[Errors, String] = {
    import spark.sparkContext.{hadoopConfiguration => hc}

    def err(msg: String): Either[Errors, String] = Left(ConfigError(path, msg) :: Nil)

    try {
      val textFile = CloudUtils.getTextBlob(uri.toString)
      if (textFile.length == 0) {
        err(s"file at ${uri.toString} is empty")
      } else {
        Right(textFile)
      }
    } catch {
      case e: Exception => err(s"${e.getMessage}")
    }
  }  

  private def getModel(path: String, uri: URI)(implicit spark: SparkSession): Either[Errors, PipelineModel] = {
    def err(msg: String): Either[Errors, PipelineModel] = Left(ConfigError(path, msg) :: Nil)

    try {
      Right(PipelineModel.load(uri.toString))
    } catch {
      case e: Exception => err(s"${e.getMessage}")
    }
  }    

  private def getExtractColumns(parsedURI: Either[Errors, Option[URI]], uriKey: String, authentication: Either[Errors, Option[Authentication]])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Either[Errors, List[ExtractColumn]] = {
    val schema: Either[Errors, Option[String]] = parsedURI.rightFlatMap { optUri => 
        optUri match {
          case Some(uri) => 
            authentication.right.map(auth => CloudUtils.setHadoopConfiguration(auth) )
            getBlob(uriKey, uri).rightFlatMap(text => Right(Option(text)))
          case None => Right(None)
        }
    }        

    schema.rightFlatMap { sch =>
      val cols = sch.map{ s => MetadataSchema.parseJsonMetadata(s) }.getOrElse(Right(Nil))

      cols match {
        case Left(errs) => Left(errs.map( e => ConfigError("schema", e) ))
        case Right(extractColumns) => Right(extractColumns)
      }
    }
  }

  private def getJDBCDriver(path: String, uri: String): Either[Errors, java.sql.Driver] = {
    def err(msg: String): Either[Errors, java.sql.Driver] = Left(ConfigError(path, msg) :: Nil)

    try {
      Right(DriverManager.getDriver(uri))
    } catch {
      case e: Exception => err(s"${e.getMessage}")
    }
  }  

  // validateSQL uses the parsePlan method to verify if the sql command is parseable/valid. it will not check table existence.
  private def validateSQL(path: String, sql: String)(implicit spark: SparkSession): Either[Errors, String] = {
    def err(msg: String): Either[Errors, String] = Left(ConfigError(path, msg) :: Nil)

    try {
      val parser = spark.sessionState.sqlParser
      parser.parsePlan(sql)
      Right(sql)
    } catch {
      case e: Exception => err(s"${e.getMessage}")
    }
  }  

  def readAvroExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputURI = getValue[String]("inputURI")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")

    (name, inputURI, outputView, persist, numPartitions, authentication) match {
      case (Right(n), Right(in), Right(ov), Right(p), Right(np), Right(auth)) => 
        Right(AvroExtract(n, Nil, ov, new URI(in), auth, params, p, np))
      case _ =>
        val allErrors: Errors = List(name, inputURI, outputView, persist, numPartitions, authentication).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  // extract
  def readDelimitedExtract(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val input = if(c.hasPath("inputView")) getValue[String]("inputView") else getValue[String]("inputURI")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val header = getOptionalValue[Boolean]("header")
    val authentication = readAuthentication("authentication")

    val uriKey = "schemaURI"
    val stringURI = getOptionalValue[String](uriKey)
    val parsedURI: Either[Errors, Option[URI]] = stringURI.rightFlatMap(optURI => 
      optURI match { 
        case Some(uri) => parseURI(uriKey, uri).rightFlatMap(parsedURI => Right(Option(parsedURI)))
        case None => Right(None)
      }
    )
    val extractColumns = getExtractColumns(parsedURI, uriKey, authentication)

    // @TODO: FiX delimited OPTIONS
    val delimited = Delimited()
    val delimiter = if (c.hasPath("delimiter")) {
      c.getString("delimiter") match {
        case "Comma" => Delimiter.Comma
        case "DefaultHive" => Delimiter.DefaultHive
        case "Pipe" => Delimiter.Pipe
      }
    } else delimited.sep
    val quote = if (c.hasPath("quote")) {
      c.getString("quote") match {
        case "DoubleQuote" => QuoteCharacter.DoubleQuote
        case "SingleQuote" => QuoteCharacter.SingleQuote
        case "None" => QuoteCharacter.Disabled
      }
    } else delimited.quote

    (name, input, extractColumns, outputView, persist, numPartitions, header, authentication) match {
      case (Right(n), Right(in), Right(cols), Right(ov), Right(p), Right(np), Right(head), Right(auth)) => 

        val header = head match {
          case Some(value) => value
          case None => delimited.header
        }

        val input = if(c.hasPath("inputView")) Left(in) else Right(new URI(in))
        val extract = DelimitedExtract(n, cols, ov, input, Delimited(header=header, sep=delimiter, quote=quote), auth, params, p, np)
        Right(extract)
      case _ =>
        val allErrors: Errors = List(name, input, extractColumns, outputView, persist, numPartitions, header, authentication).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readHTTPExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val uriKey = "uri"
    val inputURI = getValue[String](uriKey)
    val parsedURI = inputURI.rightFlatMap(uri => parseURI(uriKey, uri))
    val headers = readMap("headers", c)
    val validStatusCodes = if (c.hasPath("validStatusCodes")) Some(c.getIntList("validStatusCodes").asScala.map(f => f.toInt).toList) else None

    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")

    (name, parsedURI, outputView, persist, numPartitions) match {
      case (Right(n), Right(uri), Right(ov), Right(p), Right(np)) => 
        Right(HTTPExtract(n, uri, headers, validStatusCodes, ov, params, p, np))
      case _ =>
        val allErrors: Errors = List(name, parsedURI, outputView, persist, numPartitions).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readJDBCExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val jdbcURL = getValue[String]("jdbcURL")
    val driver = jdbcURL.rightFlatMap(uri => getJDBCDriver("jdbcURL", uri))
    val tableName = getValue[String]("tableName")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val fetchsize = getOptionalValue[Int]("fetchsize")
    val customSchema = getOptionalValue[String]("customSchema")

    (name, outputView, persist, jdbcURL, driver, tableName, numPartitions, fetchsize, customSchema) match {
      case (Right(n), Right(ov), Right(p), Right(ju), Right(d), Right(tn), Right(np), Right(fs), Right(cs)) => 
        Right(JDBCExtract(n, ov, ju, tn, np, fs, cs, d, params, p))
      case _ =>
        val allErrors: Errors = List(name, outputView, persist, jdbcURL, driver, tableName, numPartitions, fetchsize, customSchema).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }   

  def readJSONExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val input = if(c.hasPath("inputView")) getValue[String]("inputView") else getValue[String]("inputURI")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val multiLine = getOptionalValue[Boolean]("multiLine")
    val authentication = readAuthentication("authentication")

    (name, input, outputView, persist, numPartitions, multiLine, authentication) match {
      case (Right(n), Right(in), Right(ov), Right(p), Right(np), Right(ml), Right(auth)) => 
        val input = if(c.hasPath("inputView")) Left(in) else Right(new URI(in))

        val json = JSON()
        val multiLine = ml match {
          case Some(b: Boolean) => b
          case _ => json.multiLine
        }
        val extract = JSONExtract(n, Nil, ov, input, JSON(multiLine=multiLine), auth, params, p, np)
        Right(extract)
      case _ =>
        val allErrors: Errors = List(name, input, outputView, persist, numPartitions, multiLine, authentication).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  def readKafkaExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val outputView = getValue[String]("outputView")
    val topic = getValue[String]("topic")
    val bootstrapServers = getValue[String]("bootstrapServers")
    val groupID = getValue[String]("groupID")

    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")

    val maxPollRecords = getOptionalValue[Int]("maxPollRecords")
    val timeout = getOptionalValue[Long]("timeout")
    val autoCommit = getOptionalValue[Boolean]("autoCommit")

    (name, outputView, topic, bootstrapServers, groupID, persist, numPartitions, maxPollRecords, timeout, autoCommit) match {
      case (Right(n), Right(ov), Right(t), Right(bs), Right(g), Right(p), Right(np), Right(mpr), Right(time), Right(ac)) => 
        Right(KafkaExtract(n, ov, t, bs, g, mpr, time, ac, params, p, np))
      case _ =>
        val allErrors: Errors = List(name, outputView, topic, bootstrapServers, groupID, persist, numPartitions, maxPollRecords, timeout, autoCommit).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readORCExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputURI = getValue[String]("inputURI")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")

    (name, inputURI, outputView, persist, numPartitions, authentication) match {
      case (Right(n), Right(in), Right(ov), Right(p), Right(np), Right(auth)) => 
        Right(ORCExtract(n, Nil, ov, new URI(in), auth, params, p, np))
      case _ =>
        val allErrors: Errors = List(name, inputURI, outputView, persist, numPartitions, authentication).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readParquetExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputURI = getValue[String]("inputURI")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")

    (name, inputURI, outputView, persist, numPartitions, authentication) match {
      case (Right(n), Right(in), Right(ov), Right(p), Right(np), Right(auth)) => 
        Right(ParquetExtract(n, Nil, ov, new URI(in), auth, params, p, np))
      case _ =>
        val allErrors: Errors = List(name, inputURI, outputView, persist, numPartitions, authentication).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  def readXMLExtract(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputURI = getValue[String]("inputURI")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")

    (name, inputURI, outputView, persist, numPartitions, authentication) match {
      case (Right(n), Right(in), Right(ov), Right(p), Right(np), Right(auth)) => 
        Right(XMLExtract(n, Nil, ov, new URI(in), auth, params, p, np))
      case _ =>
        val allErrors: Errors = List(name, inputURI, outputView, persist, numPartitions, authentication).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  // transform
  def readDiffTransform(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputLeftView = getValue[String]("inputLeftView")
    val inputRightView = getValue[String]("inputRightView")
    val outputIntersectionView = getOptionalValue[String]("outputIntersectionView")
    val outputLeftView = getOptionalValue[String]("outputLeftView")
    val outputRightView = getOptionalValue[String]("outputRightView")
    val persist = getValue[Boolean]("persist")

    (name, inputLeftView, inputRightView, outputIntersectionView, outputLeftView, outputRightView, persist) match {
      case (Right(n), Right(ilv), Right(irv), Right(oiv), Right(olv), Right(orv), Right(p)) => 
        Right(DiffTransform(n, ilv, irv, oiv, olv, orv, params, p))
      case _ =>
        val allErrors: Errors = List(name, inputLeftView, inputRightView, outputIntersectionView, outputLeftView, outputRightView, persist).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  } 

  def readJSONTransform(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")

    (name, inputView, outputView, persist) match {
      case (Right(n), Right(iv), Right(ov), Right(p)) => 
        Right(JSONTransform(n, iv, ov, params, p))
      case _ =>
        val allErrors: Errors = List(name, inputView, outputView, persist).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readMLTransform(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val uriKey = "inputURI"
    val inputURI = getValue[String](uriKey)
    val parsedURI = inputURI.rightFlatMap(uri => parseURI(uriKey, uri))
    val authentication = readAuthentication("authentication")

    val inputModel = parsedURI.rightFlatMap { uri =>
        authentication.right.map(auth => CloudUtils.setHadoopConfiguration(auth))
        getModel(uriKey, uri)
    }

    val inputView = getValue[String]("inputView")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")

    (name, inputURI, inputModel, inputView, outputView, persist) match {
      case (Right(n), Right(in), Right(mod), Right(iv), Right(ov), Right(p)) => 
        val uri = new URI(in)
        Right(MLTransform(n, uri, mod, iv, ov, params, p))
      case _ =>
        val allErrors: Errors = List(name, inputURI, parsedURI, inputModel, inputView, outputView, persist).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  def readSQLTransform(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val uriKey = "inputURI"
    val inputURI = getValue[String](uriKey)
    val parsedURI = inputURI.rightFlatMap(uri => parseURI(uriKey, uri))
    val authentication = readAuthentication("authentication")  
    val inputSQL = parsedURI.rightFlatMap { uri =>
        authentication.right.map(auth => CloudUtils.setHadoopConfiguration(auth))  
        getBlob(uriKey, uri)
    }
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")
    val sqlParams = readMap("sqlParams", c)

    // try to verify if sql is technically valid against HQL dialect (will not check dependencies)
    val validSQL = inputSQL.rightFlatMap { sql =>
      validateSQL(uriKey, SQLUtils.injectParameters(sql, sqlParams))
    }

    (name, parsedURI, inputSQL, validSQL, outputView, persist) match {
      case (Right(n), Right(uri), Right(isql), Right(vsql), Right(ov), Right(p)) => 

        if (vsql.toLowerCase() contains "now") {
          logger.warn()
            .field("event", "validateConfig")
            .field("name", n)
            .field("type", "SQLTransform")              
            .field("message", "sql contains NOW() function which may produce non-deterministic results")       
            .log()   
        } 

        if (vsql.toLowerCase() contains "current_date") {
          logger.warn()
            .field("event", "validateConfig")
            .field("name", n)
            .field("type", "SQLTransform")              
            .field("message", "sql contains CURRENT_DATE() function which may produce non-deterministic results")       
            .log()   
        }

        if (vsql.toLowerCase() contains "current_timestamp") {
          logger.warn()
            .field("event", "validateConfig")
            .field("name", n)
            .field("type", "SQLTransform")              
            .field("message", "sql contains CURRENT_TIMESTAMP() function which may produce non-deterministic results")       
            .log()   
        }        

        Right(SQLTransform(n, uri, vsql, ov, params, sqlParams, p))
      case _ =>
        val allErrors: Errors = List(name, inputURI, parsedURI, inputSQL, validSQL, outputView, persist).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  } 

  def readTensorFlowServingTransform(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputView = getValue[String]("outputView")
    val inputFields = readMap("inputFields", c)
    val outputFields = readMap("outputFields", c)
    val hostname = getValue[String]("hostname")
    val port = getValue[Int]("port")
    val modelName = getValue[String]("modelName")
    val signatureName = getValue[String]("signatureName")
    val persist = getValue[Boolean]("persist")

    (name, inputView, outputView, hostname, port, modelName, signatureName, persist) match {
      case (Right(n), Right(iv), Right(ov), Right(hn), Right(pt), Right(mn), Right(sn), Right(p)) => 
        Right(TensorFlowServingTransform(n, iv, ov, inputFields, outputFields, hn, pt, mn, sn, params, p))
      case _ =>
        val allErrors: Errors = List(name, inputView, outputView, hostname, port, modelName, signatureName, persist).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  def readTypingTransform(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val uriKey = "inputURI"
    val inputURI = getValue[String](uriKey)
    val parsedURI = inputURI.rightFlatMap(uri => parseURI(uriKey, uri)).rightFlatMap(uri => Right(Option(uri)))
    val authentication = readAuthentication("authentication")  

    val extractColumns = getExtractColumns(parsedURI, uriKey, authentication)

    val inputView = getValue[String]("inputView")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist")

    (name, extractColumns, inputView, outputView, persist) match {
      case (Right(n), Right(cols), Right(iv), Right(ov), Right(p)) => 
        Right(TypingTransform(n, cols, iv, ov, params, p))
      case _ =>
        val allErrors: Errors = List(name, inputURI, parsedURI, extractColumns, inputView, outputView, persist, authentication).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }   

  // load

  def readAvroLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputURI = getValue[String]("outputURI")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")  
    val saveMode = readSaveMode("saveMode")

    (name, inputView, outputURI, numPartitions, authentication, saveMode) match {
      case (Right(n), Right(iv), Right(out), Right(np), Right(auth), Right(sm)) => 
        val uri = new URI(out)
        Right(AvroLoad(n, iv, uri, partitionBy, np, auth, sm, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, outputURI, numPartitions, authentication, saveMode).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }    

  def readAzureEventHubsLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val namespaceName = getValue[String]("namespaceName")
    val eventHubName = getValue[String]("eventHubName")
    val sharedAccessSignatureKeyName = getValue[String]("sharedAccessSignatureKeyName")
    val sharedAccessSignatureKey = getValue[String]("sharedAccessSignatureKey")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val retryMinBackoff = getOptionalValue[Long]("retryMinBackoff")
    val retryMaxBackoff = getOptionalValue[Long]("retryMaxBackoff")
    val retryCount = getOptionalValue[Int]("retryCount")

    (name, inputView, namespaceName, eventHubName, sharedAccessSignatureKeyName, sharedAccessSignatureKey, numPartitions, retryMinBackoff, retryMaxBackoff, retryCount) match {
      case (Right(n), Right(iv), Right(nn), Right(ehn), Right(saskn), Right(sask), Right(np), Right(rmin), Right(rmax), Right(rcount)) => 
        Right(AzureEventHubsLoad(n, iv, nn, ehn, saskn, sask, np, rmin, rmax, rcount, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, namespaceName, eventHubName, sharedAccessSignatureKeyName, sharedAccessSignatureKey, numPartitions, retryMinBackoff, retryMaxBackoff, retryCount).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }    

  def readDelimitedLoad(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputURI = getValue[String]("outputURI")
    val header = getOptionalValue[Boolean]("header")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil    
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")  
    val saveMode = readSaveMode("saveMode")

    // @TODO: FiX delimited OPTIONS
    val delimited = Delimited()
    val delimiter = if (c.hasPath("delimiter")) {
      c.getString("delimiter") match {
        case "Comma" => Delimiter.Comma
        case "DefaultHive" => Delimiter.DefaultHive
        case "Pipe" => Delimiter.Pipe
      }
    } else delimited.sep
    val quote = if (c.hasPath("quote")) {
      c.getString("quote") match {
        case "DoubleQuote" => QuoteCharacter.DoubleQuote
        case "SingleQuote" => QuoteCharacter.SingleQuote
        case "None" => QuoteCharacter.Disabled
      }
    } else delimited.quote

    (name, inputView, outputURI, header, numPartitions, authentication, saveMode) match {
      case (Right(n), Right(in), Right(out), Right(head), Right(np), Right(auth), Right(sm)) => 
        val uri = new URI(out)

        val header = head match {
          case Some(value) => value
          case None => delimited.header
        }

        val load = DelimitedLoad(n, in, uri, Delimited(header=header, sep=delimiter, quote=quote), partitionBy, np, auth, sm, params)
        Right(load)
      case _ =>
        val allErrors: Errors = List(name, inputView, outputURI, header, authentication, numPartitions, saveMode).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }    
  
  def readHTTPLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val uriKey = "outputURI"
    val inputURI = getValue[String](uriKey)
    val parsedURI = inputURI.rightFlatMap(uri => parseURI(uriKey, uri))
    val headers = readMap("headers", c)
    val validStatusCodes = if (c.hasPath("validStatusCodes")) Some(c.getIntList("validStatusCodes").asScala.map(f => f.toInt).toList) else None

    (name, parsedURI, inputView) match {
      case (Right(n), Right(uri), Right(iv)) => 
        Right(HTTPLoad(n, iv, uri, headers, validStatusCodes, params))
      case _ =>
        val allErrors: Errors = List(name, parsedURI, inputView).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readJDBCLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val jdbcURL = getValue[String]("jdbcURL")
    val driver = jdbcURL.rightFlatMap(uri => getJDBCDriver("jdbcURL", uri))
    val tableName = getValue[String]("tableName")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil    
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val isolationLevel = getOptionalValue[String]("isolationLevel")
    val batchsize = getOptionalValue[Int]("batchsize")
    val truncate = getOptionalValue[Boolean]("truncate")
    val createTableOptions = getOptionalValue[String]("createTableOptions")
    val createTableColumnTypes = getOptionalValue[String]("createTableColumnTypes")
    val saveMode = readSaveMode("saveMode")
    val bulkload = getOptionalValue[Boolean]("bulkload")

    (name, inputView, jdbcURL, driver, tableName, numPartitions, isolationLevel, batchsize, truncate, createTableOptions, createTableColumnTypes, saveMode, bulkload) match {
      case (Right(n), Right(iv), Right(ju), Right(d), Right(tn), Right(np), Right(il), Right(bs), Right(t), Right(cto), Right(ctct), Right(sm), Right(bl)) => 
        Right(JDBCLoad(n, iv, ju, tn, partitionBy, np, il, bs, t, cto, ctct, sm, d, bl, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, jdbcURL, driver, tableName, numPartitions, isolationLevel, batchsize, truncate, createTableOptions, createTableColumnTypes, saveMode, bulkload).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }      

  def readJSONLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputURI = getValue[String]("outputURI")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")  
    val saveMode = readSaveMode("saveMode")

    (name, inputView, outputURI, numPartitions, authentication, saveMode) match {
      case (Right(n), Right(iv), Right(out), Right(np), Right(auth), Right(sm)) => 
        val uri = new URI(out)
        Right(JSONLoad(n, iv, uri, partitionBy, np, auth, sm, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, outputURI, numPartitions, authentication, saveMode).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  def readKafkaLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val topic = getValue[String]("topic")
    val bootstrapServers = getValue[String]("bootstrapServers")
    val acks = getValue[Int]("acks")

    val retries = getOptionalValue[Int]("retries")
    val batchSize = getOptionalValue[Int]("batchSize")
    val numPartitions = getOptionalValue[Int]("numPartitions")

    (name, inputView, topic, bootstrapServers, acks, retries, batchSize, numPartitions) match {
      case (Right(n), Right(iv), Right(t), Right(bss), Right(a), Right(r), Right(bs), Right(np)) => 
        Right(KafkaLoad(n, iv, t, bss, a, np, r, bs, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, topic, bootstrapServers, acks, retries, batchSize, numPartitions).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }   

  def readORCLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputURI = getValue[String]("outputURI")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")  
    val saveMode = readSaveMode("saveMode")

    (name, inputView, outputURI, numPartitions, authentication, saveMode) match {
      case (Right(n), Right(iv), Right(out), Right(np), Right(auth), Right(sm)) => 
        val uri = new URI(out)
        Right(ORCLoad(n, iv, uri, partitionBy, np, auth, sm, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, outputURI, numPartitions, authentication, saveMode).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readParquetLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputURI = getValue[String]("outputURI")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")  
    val saveMode = readSaveMode("saveMode")

    (name, inputView, outputURI, numPartitions, authentication, saveMode) match {
      case (Right(n), Right(iv), Right(out), Right(np), Right(auth), Right(sm)) => 
        val uri = new URI(out)
        Right(ParquetLoad(n, iv, uri, partitionBy, np, auth, sm, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, outputURI, numPartitions, authentication, saveMode).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  def readXMLLoad(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val outputURI = getValue[String]("outputURI")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val authentication = readAuthentication("authentication")  
    val saveMode = readSaveMode("saveMode")

    (name, inputView, outputURI, numPartitions, authentication, saveMode) match {
      case (Right(n), Right(iv), Right(out), Right(np), Right(auth), Right(sm)) => 
        val uri = new URI(out)
        Right(XMLLoad(n, iv, uri, partitionBy, np, auth, sm, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, outputURI, numPartitions, authentication, saveMode).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  

  // execute

  def readHTTPExecute(name: StringConfigValue, params: Map[String, String])(implicit logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val uri = getValue[String]("uri")
    val headers = readMap("headers", c)
    val payloads = readMap("payloads", c)
    val validStatusCodes = if (c.hasPath("validStatusCodes")) Some(c.getIntList("validStatusCodes").asScala.map(f => f.toInt).toList) else None

    (name, uri) match {
      case (Right(n), Right(u)) => 
        val uri = new URI(u)
        Right(HTTPExecute(n, uri, headers, payloads, validStatusCodes, params))
      case _ =>
        val allErrors: Errors = List(uri).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  def readJDBCExecute(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val authentication = readAuthentication("authentication")  

    val uriKey = "inputURI"
    val inputURI = getValue[String](uriKey)
    val parsedURI = inputURI.rightFlatMap(uri => parseURI(uriKey, uri))
    val inputSQL = parsedURI.rightFlatMap { uri =>
        authentication.right.map(auth => CloudUtils.setHadoopConfiguration(auth))    
        getBlob(uriKey, uri)
    }

    val jdbcUrl = getValue[String]("url")
    val user = getOptionalValue[String]("user")
    val password = getOptionalValue[String]("password")

    val sqlParams = readMap("sqlParams", c)    

    val driverExists = jdbcUrl match {
      case Right(url) => JDBCUtils.checkDriverExists(url)
      case _ => false
    }

    (name, inputURI, inputSQL, jdbcUrl, user, password) match {
      case (Right(n), Right(in), Right(sql), Right(url), Right(u), Right(p)) if driverExists => 
        val sqlFileUri = new URI(in)
        
        Right(JDBCExecute(n, sqlFileUri, url, u, p, sql, sqlParams, params))
      case _ =>
        val configErrors = List(name, inputURI, parsedURI, inputSQL, jdbcUrl, user, password).collect{ case Left(errs) => errs }.flatten

        val allErrors = if (!driverExists) {
          val driverError = ConfigError("url", "No jdbc driver found")
          driverError :: configErrors
        } else {
          configErrors
        }

        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }

  def readKafkaCommitExecute(name: StringConfigValue, params: Map[String, String])(implicit c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val inputView = getValue[String]("inputView")
    val bootstrapServers = getValue[String]("bootstrapServers")
    val groupID = getValue[String]("groupID")

    (name, inputView, bootstrapServers, groupID) match {
      case (Right(n), Right(iv), Right(bs), Right(g)) => 
        Right(KafkaCommitExecute(n, iv, bs, g, params))
      case _ =>
        val allErrors: Errors = List(name, inputView, bootstrapServers, groupID).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }    

  // validate

  def readEqualityValidate(name: StringConfigValue, params: Map[String, String])(implicit logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val leftView = getValue[String]("leftView")
    val rightView = getValue[String]("rightView")

    (name, leftView, rightView) match {
      case (Right(n), Right(l), Right(r)) => 
        Right(EqualityValidate(n, l, r, params))
      case _ =>
        val allErrors: Errors = List(name, leftView, rightView).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }  
  
  def readSQLValidate(name: StringConfigValue, params: Map[String, String])(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, c: Config): Either[StageError, PipelineStage] = {
    import ConfigReader._

    val authentication = readAuthentication("authentication")  

    val uriKey = "inputURI"
    val inputURI = getValue[String](uriKey)
    val parsedURI = inputURI.rightFlatMap(uri => parseURI(uriKey, uri))
    val inputSQL = parsedURI.rightFlatMap { uri =>
        authentication.right.map(auth => CloudUtils.setHadoopConfiguration(auth))    
        getBlob(uriKey, uri)
    }

    val sqlParams = readMap("sqlParams", c) 

    // try to verify if sql is technically valid against HQL dialect (will not check dependencies)
    val validSQL = inputSQL.rightFlatMap { sql =>
      validateSQL(uriKey, SQLUtils.injectParameters(sql, sqlParams))
    }    

    (name, parsedURI, inputSQL, validSQL) match {
      case (Right(n), Right(uri), Right(sql), Right(vsql)) => 
        Right(SQLValidate(n, uri, sql, sqlParams, params))
      case _ =>
        val allErrors: Errors = List(name, parsedURI, inputSQL, validSQL).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(stageName, allErrors)
        Left(err)
    }
  }    

  def readPipeline(c: Config, uri: URI)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger, env: String): Either[List[Error], ETLPipeline] = {
    import ConfigReader._

    val startTime = System.currentTimeMillis() 
    val configStages = c.getObjectList("stages")

    logger.info()
      .field("event", "validateConfig")
      .field("uri", uri.toString)        
      .log()    

    val pipelineStages: List[Either[StageError, PipelineStage]] =
      (for (stage <- configStages.asScala) yield {
        implicit val s = stage.toConfig
        val name: StringConfigValue = getValue[String]("name")
        val params = readMap("params", s)
        val environments = if (s.hasPath("environments")) s.getStringList("environments").asScala.toList else Nil
        val _type = getValue[String]("type")

        // deprecation message to override empty environments
        val depricationEnvironments = environments match {
          case Nil => {
            logger.warn()
              .field("event", "deprecation")
              .field("name", name.right.getOrElse("unnamed stage"))
              .field("type", _type.right.getOrElse("unknown"))                
              .field("message", "'environments' key will be required in next release. defaulting to ['prd', 'ppd', 'tst', 'dev']")        
              .log()

            List("prd", "ppd", "tst", "dev")
          }
          case _ => environments
        }

        // skip stange if not in environment
        if (!depricationEnvironments.contains(env)) {
            logger.info()
              .field("event", "validateConfig")
              .field("name", name.right.getOrElse("unnamed stage"))
              .field("type", _type.right.getOrElse("unknown"))              
              .field("message", "skipping stage due to environment configuration")       
              .field("skipStage", true)
              .field("environment", env)               
              .list("environments", depricationEnvironments.asJava)               
              .log()    
          
          None
        } else {
            logger.info()
              .field("event", "validateConfig")
              .field("name", name.right.getOrElse("unnamed stage"))
              .field("type", _type.right.getOrElse("unknown"))              
              .field("skipStage", false)
              .field("environment", env)               
              .list("environments", depricationEnvironments.asJava)               
              .log()   

          _type match {

            case Right("AvroExtract") => Option(readAvroExtract(name, params))
            case Right("DelimitedExtract") => Option(readDelimitedExtract(name, params))
            case Right("HTTPExtract") => Option(readHTTPExtract(name, params))
            case Right("JDBCExtract") => Option(readJDBCExtract(name, params))
            case Right("JSONExtract") => Option(readJSONExtract(name, params))
            case Right("KafkaExtract") => Option(readKafkaExtract(name, params))
            case Right("ORCExtract") => Option(readORCExtract(name, params))
            case Right("ParquetExtract") => Option(readParquetExtract(name, params))
            case Right("XMLExtract") => Option(readXMLExtract(name, params))

            case Right("DiffTransform") => Option(readDiffTransform(name, params))
            case Right("JSONTransform") => Option(readJSONTransform(name, params))
            case Right("MLTransform") => Option(readMLTransform(name, params))
            case Right("SQLTransform") => Option(readSQLTransform(name, params))
            case Right("TensorFlowServingTransform") => Option(readTensorFlowServingTransform(name, params))
            case Right("TypingTransform") => Option(readTypingTransform(name, params))

            case Right("AvroLoad") => Option(readAvroLoad(name, params))
            case Right("AzureEventHubsLoad") => Option(readAzureEventHubsLoad(name, params))
            case Right("DelimitedLoad") => Option(readDelimitedLoad(name, params))
            case Right("HTTPLoad") => Option(readHTTPLoad(name, params))
            case Right("JDBCLoad") => Option(readJDBCLoad(name, params))
            case Right("JSONLoad") => Option(readJSONLoad(name, params))
            case Right("KafkaLoad") => Option(readKafkaLoad(name, params))
            case Right("ORCLoad") => Option(readORCLoad(name, params))
            case Right("ParquetLoad") => Option(readParquetLoad(name, params))
            case Right("XMLLoad") => Option(readXMLLoad(name, params))

            case Right("HTTPExecute") => Option(readHTTPExecute(name, params))
            case Right("JDBCExecute") => Option(readJDBCExecute(name, params))
            case Right("KafkaCommitExecute") => Option(readKafkaCommitExecute(name, params))

            case Right("EqualityValidate") => Option(readEqualityValidate(name, params))
            case Right("SQLValidate") => Option(readSQLValidate(name, params))
            
            case _ => Option(Left(StageError("unknown", ConfigError("stages", s"Unknown stage type: '${_type}'") :: Nil)))
          }
        }
      }).flatten.toList

    val (stages, errors) = pipelineStages.foldLeft[(List[PipelineStage], List[StageError])]( (Nil, Nil) ) { case ( (stages, errs), stageOrError ) =>
      stageOrError match {
        case Right(s) => (s :: stages, errs)
        case Left(err) => (stages, err :: errs)
      }
    }

    logger.info()
      .field("event", "exit")
      .field("type", "readPipeline")        
      .field("duration", System.currentTimeMillis() - startTime)
      .log()      

    errors match {
      case Nil => Right(ETLPipeline(stages.reverse))
      case _ => Left(errors.reverse)
    }
  }

}
