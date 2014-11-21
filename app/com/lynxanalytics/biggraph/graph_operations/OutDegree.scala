package com.lynxanalytics.biggraph.graph_operations

import org.apache.spark
import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.Implicits._

object OutDegree {
  class Input() extends MagicInputSignature {
    val src = vertexSet
    val dst = vertexSet
    val es = edgeBundle(src, dst)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input)
      extends MagicOutput(instance) {
    val outDegree = vertexAttribute[Double](inputs.src.entity)
  }

  def outDegree(es: EdgeBundle)(implicit manager: MetaGraphManager) = {
    import Scripting._
    val op = OutDegree()
    op(op.es, es).result.outDegree
  }
  def inDegree(es: EdgeBundle)(implicit manager: MetaGraphManager) = {
    outDegree(ReverseEdges.run(es))
  }
  def allDegree(es: EdgeBundle)(implicit manager: MetaGraphManager) = {
    DeriveJS.add(inDegree(es), outDegree(es))
  }
}
import OutDegree._
case class OutDegree() extends TypedMetaGraphOp[Input, Output] {
  @transient override lazy val inputs = new Input
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val vsA = inputs.src.rdd
    val outdegrees = inputs.es.rdd
      .map { case (_, edge) => edge.src -> 1.0 }
      .reduceBySortedKey(vsA.partitioner.get, _ + _)
    val result = vsA.sortedLeftOuterJoin(outdegrees).mapValues(_._2.getOrElse(0.0))
    output(o.outDegree, result)
  }
}
