// Some handy extensions to the SparkContext interface.
package com.lynxanalytics.biggraph.graph_api

import org.apache.spark
import scala.util.Random

import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.graph_util.HadoopFile
import com.lynxanalytics.biggraph.graph_util.FileBasedObjectCache

case class RuntimeContext(sparkContext: spark.SparkContext,
                          ioContext: io.IOContext,
                          broadcastDirectory: HadoopFile) {
  // A suitable partitioner for an RDD of N rows.
  def partitionerForNRows(n: Long): spark.Partitioner =
    new spark.HashPartitioner((n / io.EntityIO.verticesPerPartition).ceil.toInt max 1)
  lazy val onePartitionPartitioner: spark.Partitioner =
    new spark.HashPartitioner(1)
}
