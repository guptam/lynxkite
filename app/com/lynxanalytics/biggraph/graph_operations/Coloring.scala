// Generates an approximation of the optimal graph coloring
package com.lynxanalytics.biggraph.graph_operations

import com.lynxanalytics.biggraph.graph_api.{ DataSet, OutputBuilder, RuntimeContext, _ }
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.spark_util.RDDUtils

import org.apache.spark.rdd.RDD

object Coloring extends OpFromJson {
  class Input extends MagicInputSignature {
    val (vs, es) = graph
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val coloring = vertexAttribute[Double](inputs.vs.entity)

  }
  def fromJson(j: JsValue) = Coloring()
}
import Coloring._
case class Coloring()
    extends TypedMetaGraphOp[Input, Output] {
  override val isHeavy = true
  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val edges = inputs.es.rdd
    val vertices = inputs.vs.rdd
    val vertexPartitioner = vertices.partitioner.get
    val edgePartitioner = edges.partitioner.get
    val betterPartitioner = RDDUtils.maxPartitioner(vertexPartitioner, edgePartitioner)

    val maxIterations = 10

    case class PertColoring(result: Option[(Double, AttributeRDD[Double])])

    /* pertColoring works on a directed acylic graph (DAG) and colors each vertex according to the length of the longest
     * path starting from that vertex. The DAG is given as an input by a list of its directed edges.
     * The tooManyColors parameter is there for stop the pertColoring if we were to have more colors than we want -
     * it's used when we already have some coloring and so we are only interested in colorings with fewer colors.
     * If pertColoring is stopped due to reaching too many colors then it returns None, otherwise it returns
     * Some(number of colors needed for the new coloring, the new Coloring)
     * Name comes from the PERT method which is also based on finding the longest paths from each vertex in a
     * directed graph: https://en.wikipedia.org/wiki/Program_evaluation_and_review_technique
     */
    @annotation.tailrec
    def pertColoring(
      directedEdges: RDD[(Long, Long)], coloringSoFar: AttributeRDD[Double],
      nextColor: Double, tooManyColors: Double): PertColoring = {
      if (nextColor >= tooManyColors) PertColoring(None)
      else {
        val notYetColored = directedEdges.mapValues(dst => nextColor).distinct
        if (notYetColored.isEmpty()) PertColoring(Some(nextColor - 1, coloringSoFar))
        else {
          val newDirectedEdges = directedEdges.map(e => e.swap).join(notYetColored)
            .map { case (dst, (src, color)) => (src, dst) }
          val newColoringSoFar = coloringSoFar.leftOuterJoin(notYetColored).mapValues {
            case (oldColor, newColorOpt) => newColorOpt.getOrElse(oldColor)
          }.sortUnique(vertexPartitioner)
          pertColoring(newDirectedEdges, newColoringSoFar, nextColor + 1.0, tooManyColors)
        }
      }
    }

    val edgesWithoutID = edges.map { case (id, e) => (e.src, e.dst) }
      .filter { case (src, dst) => src != dst }.distinct()

    // directs the edges to create a DAG according to the input attribute: edges go from lower to higher attribute
    def directEdgesFromOrdering(ordering: AttributeRDD[Double]) = {
      // RDD of (dst, (src, order of src))
      val directedEdges2 = ordering.join(edgesWithoutID).map { case (src, (srcOrd, dst)) => (dst, (src, srcOrd)) }
      // RDD of ((src, order of src), (dst, order of dst)
      val directedEdges1 = ordering.join(directedEdges2).
        map { case (dst, (dstOrd, (src, srcOrd))) => ((src, srcOrd), (dst, dstOrd)) }
      // RDD of (src, dst) where edges are directed in such a way that order of src < order of dst
      val directedEdges = directedEdges1.map {
        case ((src, srcOrd), (dst, dstOrd)) =>
          if (srcOrd < dstOrd) (src, dst)
          else if (dstOrd < srcOrd) (dst, src)
          else if (src < dst) (src, dst)
          else (dst, src)
      }
      directedEdges
    }

    /* findBetterColoring takes an already calculated coloring and tries to find a better one by trying out
     * a new ordering. We get the new ordering by taking the color mod (number of colors/2) for each vertex.
     * The idea behind this is that we want to cut up the long paths and the colors represent the length of the longest
     * path so by doing this we destroy all previous long paths - and hope that we don't create new ones.
     * We iterate it for maxIterations steps or until we don't get a worse coloring than the input coloring for that
     * iteration step.
     */
    @annotation.tailrec
    def findBetterColoring(oldColoring: AttributeRDD[Double], currentNumberOfColors: Double,
                           iterationsLeft: Int): AttributeRDD[Double] = {
      if (iterationsLeft > 0) {
        val newOrdering = oldColoring.mapValues(c => if (c % 2 < 1) c + currentNumberOfColors else c)
        val directedEdges = directEdgesFromOrdering(newOrdering)
        val startingColoring = vertices.mapValues(_ => 1.0)

        val (newNumberOfColors, newColoring) = pertColoring(directedEdges, startingColoring, 2.0, currentNumberOfColors)
          .result.getOrElse((currentNumberOfColors, oldColoring))
        if (newNumberOfColors >= currentNumberOfColors) oldColoring
        else findBetterColoring(newColoring, newNumberOfColors, iterationsLeft - 1)
      } else oldColoring
    }

    val degreeWithoutIsolatedVertices = edgesWithoutID.flatMap { case (src, dst) => Seq(src -> 1.0, dst -> 1.0) }.
      reduceBySortedKey(edgePartitioner, _ + _)

    /* We use the degree AttributeRDD to direct the edges to create directed acyclic graph (DAG).
     * We want to create DAG where the length of the longest directed path is as small as possible.
     * The DAG created from degree have the vertices with big degreegit  at the front of the topological order.
     * The idea behind singling out this ordering is that a long path in the underlying undirected graph
     * is very likely to go through vertices with high degree. If the vertices with high degree have vertices with
     * smaller degree between them along the path then the ordering based on the degree will cut up such a path.
     */
    val degree = vertices.sortUnique(betterPartitioner).sortedLeftOuterJoin(degreeWithoutIsolatedVertices).
      mapValues(_._2.getOrElse(0.0))

    /* Tries out to particular orderings for a start: the ordering based on the degrees of the vertices and another
     * one derived from it called convexOrdering - it basically puts the vertices with big degree to both ends of the
     * ordering while those with smaller degrees are in the middle.
     * So we are hoping that one of these orderings will give us a good starting coloring. Then try to improve the
     * coloring by iterating the findBetterColoring function.
     */
    def findColoring(iteration: Int) = {
      val vertexCount = vertices.count()
      val startingColoring = vertices.mapValues(_ => 1.0)
      val directedEdgesToDegreeOrdering = directEdgesFromOrdering(degree)
      val (numberOfColorsSoFar, coloringByDegreeOrdering) = pertColoring(directedEdgesToDegreeOrdering,
        startingColoring, 2, vertexCount).result.get

      findBetterColoring(coloringByDegreeOrdering, numberOfColorsSoFar, iteration)
    }

    val coloring = findColoring(maxIterations)
    output(o.coloring, coloring.sortedRepartition(vertexPartitioner))
  }
}
