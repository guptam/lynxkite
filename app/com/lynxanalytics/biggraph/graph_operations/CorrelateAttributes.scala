package com.lynxanalytics.biggraph.graph_operations

//import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.stat.Statistics
import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._

object CorrelateAttributes {
  class Input extends MagicInputSignature {
    val vertices = vertexSet
    val attrA = vertexAttribute[Double](vertices)
    val attrB = vertexAttribute[Double](vertices)
  }
  class Output(implicit instance: MetaGraphOperationInstance) extends MagicOutput(instance) {
    val correlation = scalar[Double]
  }
}
import CorrelateAttributes._
case class CorrelateAttributes() extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val attrA = inputs.attrA.rdd
    val attrB = inputs.attrB.rdd
    val joined = attrA.sortedJoin(attrB).values
    val a = joined.keys
    val b = joined.values
    val correlation = Statistics.corr(a, b, "pearson")
    output(o.correlation, correlation)
  }
}
