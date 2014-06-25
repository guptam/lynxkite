package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark
import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._

case class WeightedOutDegree() extends MetaGraphOperation {
  def signature = newSignature
    .inputEdgeBundle('edges, 'vsA -> 'vsB, create = true)
    .inputEdgeAttribute[Double]('weights, 'edges)
    .outputVertexAttribute[Double]('outdegrees, 'vsA)

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit = {
    val edges = inputs.edgeBundles('edges).rdd
    val weights = inputs.edgeAttributes('weights).runtimeSafeCast[Double].rdd

    val outdegrees = edges.join(weights)
      .map { case (_, (edge, weight)) => edge.src -> weight }
      .reduceByKey(
        inputs.vertexSets('vsA).rdd.partitioner.get,
        _ + _)
    outputs.putVertexAttribute('outdegrees, outdegrees)
  }
}
