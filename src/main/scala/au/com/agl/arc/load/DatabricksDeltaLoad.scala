package au.com.agl.arc.load

import java.net.URI
import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.sql.types._

import au.com.agl.arc.api.API._
import au.com.agl.arc.util._

object DatabricksDeltaLoad {

  def load(load: DatabricksDeltaLoad)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Option[DataFrame] = {
    val startTime = System.currentTimeMillis() 
    var stageDetail = new java.util.HashMap[String, Object]()
    stageDetail.put("type", load.getType)
    stageDetail.put("name", load.name)
    for (description <- load.description) {
      stageDetail.put("description", description)    
    }
    stageDetail.put("inputView", load.inputView)  
    stageDetail.put("outputURI", load.outputURI.toString)  
    stageDetail.put("partitionBy", load.partitionBy.asJava)
    stageDetail.put("saveMode", load.saveMode.toString.toLowerCase)

    val df = spark.table(load.inputView)   

    if (!df.isStreaming) {
      load.numPartitions match {
        case Some(partitions) => stageDetail.put("numPartitions", Integer.valueOf(partitions))
        case None => stageDetail.put("numPartitions", Integer.valueOf(df.rdd.getNumPartitions))
      }
    }

    logger.info()
      .field("event", "enter")
      .map("stage", stageDetail)      
      .log()

    val listener = ListenerUtils.addStageCompletedListener(stageDetail)

    try {
      load.partitionBy match {
        case Nil => {
          load.numPartitions match {
            case Some(n) => df.repartition(n).write.format("delta").mode(load.saveMode).save(load.outputURI.toString)
            case None => df.write.format("delta").mode(load.saveMode).save(load.outputURI.toString)
          }
        }
        case partitionBy => {
          // create a column array for repartitioning
          val partitionCols = partitionBy.map(col => df(col))
          load.numPartitions match {
            case Some(n) => df.repartition(n, partitionCols:_*).write.format("delta").partitionBy(partitionBy:_*).mode(load.saveMode).save(load.outputURI.toString)
            case None => df.repartition(partitionCols:_*).write.format("delta").partitionBy(partitionBy:_*).mode(load.saveMode).save(load.outputURI.toString)
          }
        }
      }
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stageDetail
      }
    }

    spark.sparkContext.removeSparkListener(listener)           

    logger.info()
      .field("event", "exit")
      .field("duration", System.currentTimeMillis() - startTime)
      .map("stage", stageDetail)      
      .log()

    Option(df)
  }
}