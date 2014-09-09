package com.lynxanalytics.biggraph.controllers

import org.apache.spark.SparkContext.rddToPairRDDFunctions

import com.lynxanalytics.biggraph.BigGraphEnvironment
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.MetaGraphManager.StringAsUUID
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.spark_util

case class VertexDiagramSpec(
  val vertexSetId: String,
  val filters: Seq[FEVertexAttributeFilter],
  val mode: String, // For now, one of "bucketed", "sampled".

  // ** Parameters for bucketed view **
  // Empty string means no bucketing on that axis.
  val xBucketingAttributeId: String = "",
  val xNumBuckets: Int = 1,
  val yBucketingAttributeId: String = "",
  val yNumBuckets: Int = 1,

  // ** Parameters for sampled view **
  val centralVertexIds: Seq[ID],
  // Edge bundle used to find neighborhood of the central vertex.
  val sampleSmearEdgeBundleId: String = "",
  val sizeAttributeId: String = "",
  val labelAttributeId: String = "",
  val radius: Int = 1)

case class FEVertex(
  size: Double,

  // For bucketed view:
  x: Int = 0,
  y: Int = 0,

  // For sampled view:
  id: Long = 0,
  label: String = "")

case class VertexDiagramResponse(
  val diagramId: String,
  val vertices: Seq[FEVertex],
  val mode: String, // as specified in the request

  // ** Only set for bucketed view **
  val xLabelType: String = "",
  val yLabelType: String = "",
  val xLabels: Seq[String] = Seq(),
  val yLabels: Seq[String] = Seq())

case class EdgeDiagramSpec(
  // In the context of an FEGraphRequest "idx[4]" means the diagram requested by vertexSets(4).
  // Otherwise a UUID obtained by a previous vertex diagram request.
  val srcDiagramId: String,
  val dstDiagramId: String,
  // These are copied verbatim to the response, used by the FE to identify EdgeDiagrams.
  val srcIdx: Int,
  val dstIdx: Int,
  val bundleSequence: Seq[BundleSequenceStep])

case class BundleSequenceStep(bundle: String, reversed: Boolean)

case class FEEdge(
  // idx of source vertex in the vertices Seq in the corresponding VertexDiagramResponse.
  a: Int,
  // idx of destination vertex in the vertices Seq in the corresponding VertexDiagramResponse.
  b: Int,
  size: Int)

case class EdgeDiagramResponse(
  val srcDiagramId: String,
  val dstDiagramId: String,

  // Copied from the request.
  val srcIdx: Int,
  val dstIdx: Int,

  val edges: Seq[FEEdge])

case class FEGraphRequest(
  vertexSets: Seq[VertexDiagramSpec],
  edgeBundles: Seq[EdgeDiagramSpec])

case class FEGraphResponse(
  vertexSets: Seq[VertexDiagramResponse],
  edgeBundles: Seq[EdgeDiagramResponse])

case class HistogramSpec(
  attributeId: String,
  vertexFilters: Seq[FEVertexAttributeFilter],
  numBuckets: Int)

case class HistogramResponse(
    labelType: String,
    labels: Seq[String],
    sizes: Seq[Int]) {
  val validLabelTypes = Seq("between", "bucket")
  assert(validLabelTypes.contains(labelType),
    s"$labelType is not a valid label type. They are: $validLabelTypes")
}

case class ScalarValueRequest(
  val scalarId: String)

case class ScalarValueResponse(
  val value: String)

case class CenterRequest(
  vertexSetId: String,
  filters: Seq[FEVertexAttributeFilter])

case class CenterResponse(
  val center: Seq[ID] = Seq(0))

class GraphDrawingController(env: BigGraphEnvironment) {
  implicit val metaManager = env.metaGraphManager
  implicit val dataManager = env.dataManager

  def getVertexDiagram(request: VertexDiagramSpec): VertexDiagramResponse = {
    request.mode match {
      case "bucketed" => getBucketedVertexDiagram(request)
      case "sampled" => getSampledVertexDiagram(request)
    }
  }

  def getSampledVertexDiagram(request: VertexDiagramSpec): VertexDiagramResponse = {
    val vertexSet = metaManager.vertexSet(request.vertexSetId.asUUID)
    val smearBundle = metaManager.edgeBundle(request.sampleSmearEdgeBundleId.asUUID)
    val centers = request.centralVertexIds.map(_.toLong)

    val nop = graph_operations.ComputeVertexNeighborhood(centers, request.radius)
    val nopres = nop(nop.vertices, vertexSet)(nop.edges, smearBundle).result
    val idToIdx = nopres.neighborsIdToIndex.value

    val iaaop = graph_operations.IdAsAttribute()
    val idAttr = iaaop(iaaop.vertices, vertexSet).result.vertexIds

    val fop = graph_operations.VertexAttributeFilter(graph_operations.OneOf(idToIdx.keySet))
    val sample = fop(fop.attr, idAttr).result.fvs

    cacheVertexAttributes(request.filters.map(_.attributeId))
    val filtered = FEFilters.filterMore(sample, request.filters)

    val op = graph_operations.SampledView(
      idToIdx,
      request.sizeAttributeId.nonEmpty,
      request.labelAttributeId.nonEmpty)
    var builder = op(op.vertices, vertexSet)(op.ids, idAttr)(op.filtered, filtered)
    if (request.sizeAttributeId.nonEmpty) {
      val attr = metaManager.vertexAttributeOf[Double](request.sizeAttributeId.asUUID)
      attr.rdd.cache
      builder = builder(op.sizeAttr, attr)
    }
    if (request.labelAttributeId.nonEmpty) {
      val attr = metaManager.vertexAttribute(request.labelAttributeId.asUUID)
      attr.rdd.cache
      val sattr: VertexAttribute[String] =
        if (attr.is[String]) attr.runtimeSafeCast[String]
        else graph_operations.VertexAttributeToString.run(attr)

      builder = builder(op.labelAttr, sattr)
    }
    val diagramMeta = builder.result.svVertices
    val vertices = diagramMeta.value

    VertexDiagramResponse(
      diagramId = diagramMeta.gUID.toString,
      vertices = vertices.map(v => FEVertex(id = v.id, size = v.size, label = v.label)),
      mode = "sampled")
  }

  def getDiagramFromBucketedAttributes[S, T](
    original: VertexSet,
    filtered: VertexSet,
    xBucketedAttr: graph_operations.BucketedAttribute[S],
    yBucketedAttr: graph_operations.BucketedAttribute[T]): Scalar[Map[(Int, Int), Int]] = {

    val cop = graph_operations.CountVertices()
    val originalCount = cop(cop.vertices, original).result.count
    val op = graph_operations.VertexBucketGrid(xBucketedAttr.bucketer, yBucketedAttr.bucketer)
    var builder = op(op.filtered, filtered)(op.vertices, original)(op.originalCount, originalCount)
    if (xBucketedAttr.bucketer.numBuckets > 1) {
      builder = builder(op.xAttribute, xBucketedAttr.attribute)
    }
    if (yBucketedAttr.bucketer.numBuckets > 1) {
      builder = builder(op.yAttribute, yBucketedAttr.attribute)
    }
    builder.result.bucketSizes
  }

  def getBucketedVertexDiagram(request: VertexDiagramSpec): VertexDiagramResponse = {
    val vertexSet = metaManager.vertexSet(request.vertexSetId.asUUID)
    cacheVertexAttributes(request.filters.map(_.attributeId))
    val filtered = FEFilters.filter(vertexSet, request.filters)

    val xBucketedAttr = if (request.xNumBuckets > 1 && request.xBucketingAttributeId.nonEmpty) {
      val attribute = metaManager.vertexAttribute(request.xBucketingAttributeId.asUUID)
      attribute.rdd.cache
      FEBucketers.bucketedAttribute(metaManager, dataManager, attribute, request.xNumBuckets)
    } else {
      graph_operations.BucketedAttribute[Nothing](
        null, graph_util.EmptyBucketer())
    }
    val yBucketedAttr = if (request.yNumBuckets > 1 && request.yBucketingAttributeId.nonEmpty) {
      val attribute = metaManager.vertexAttribute(request.yBucketingAttributeId.asUUID)
      attribute.rdd.cache
      FEBucketers.bucketedAttribute(metaManager, dataManager, attribute, request.yNumBuckets)
    } else {
      graph_operations.BucketedAttribute[Nothing](
        null, graph_util.EmptyBucketer())
    }

    val diagramMeta = getDiagramFromBucketedAttributes(
      vertexSet, filtered, xBucketedAttr, yBucketedAttr)
    val diagram = dataManager.get(diagramMeta).value

    val xBucketer = xBucketedAttr.bucketer
    val yBucketer = yBucketedAttr.bucketer
    val vertices = for (x <- (0 until xBucketer.numBuckets); y <- (0 until yBucketer.numBuckets))
      yield FEVertex(x = x, y = y, size = (diagram.getOrElse((x, y), 0) * 1.0).toInt)

    VertexDiagramResponse(
      diagramId = diagramMeta.gUID.toString,
      vertices = vertices,
      mode = "bucketed",
      xLabelType = xBucketer.labelType,
      yLabelType = yBucketer.labelType,
      xLabels = xBucketer.bucketLabels,
      yLabels = yBucketer.bucketLabels)
  }

  private def cacheVertexAttributes(attributeGUIDs: Seq[String]): Unit = {
    attributeGUIDs.foreach(id => metaManager.vertexAttribute(id.asUUID).rdd.cache)
  }

  private def getCompositeBundle(steps: Seq[BundleSequenceStep]): EdgeAttribute[Double] = {
    val chain = steps.map { step =>
      val bundle = metaManager.edgeBundle(step.bundle.asUUID)
      val directed = if (step.reversed) {
        metaManager.apply(graph_operations.ReverseEdges(), 'esAB -> bundle)
          .outputs.edgeBundles('esBA)
      } else bundle
      graph_operations.AddConstantAttribute.edgeDouble(directed, 1)
    }
    return new graph_util.BundleChain(chain).getCompositeEdgeBundle(metaManager)
  }

  private def tripletMapping(eb: EdgeBundle): (VertexAttribute[Array[ID]], VertexAttribute[Array[ID]]) = {
    val op = graph_operations.TripletMapping()
    val res = op(op.edges, eb).result
    val srcMapping = res.srcEdges
    val dstMapping = res.dstEdges
    srcMapping.rdd.cache
    dstMapping.rdd.cache
    return (srcMapping, dstMapping)
  }

  private def mappedAttribute[T](mapping: VertexAttribute[Array[ID]],
                                 attr: VertexAttribute[T],
                                 target: EdgeBundle): EdgeAttribute[T] = {
    val op = new graph_operations.VertexToEdgeAttribute[T]()
    val res = op(op.mapping, mapping)(op.original, attr)(op.target, target).result.mappedAttribute
    res.rdd.cache
    res
  }

  def filteredEdgesByAttribute[T](
    eb: EdgeBundle,
    tripletMapping: VertexAttribute[Array[ID]],
    fa: graph_operations.FilteredAttribute[T]): EdgeBundle = {

    val mattr = mappedAttribute(tripletMapping, fa.attribute, eb)
    val fop = graph_operations.EdgeAttributeFilter[T](fa.filter)
    fop(fop.attr, mattr).result.feb
  }

  def indexFromBucketedAttribute[T](
    original: EdgeBundle,
    base: EdgeAttribute[Int],
    tripletMapping: VertexAttribute[Array[ID]],
    ba: graph_operations.BucketedAttribute[T]): EdgeAttribute[Int] = {

    val mattr = mappedAttribute(tripletMapping, ba.attribute, original)

    val iop = graph_operations.EdgeIndexer(ba.bucketer)
    iop(iop.baseIndices, base)(iop.bucketAttribute, mattr).result.indices
  }

  def indexFromIndexingSeq(
    original: EdgeBundle,
    filtered: EdgeBundle,
    tripletMapping: VertexAttribute[Array[ID]],
    seq: Seq[graph_operations.BucketedAttribute[_]]): EdgeAttribute[Int] = {

    val startingBase: EdgeAttribute[Int] = graph_operations.AddConstantAttribute.edgeInt(filtered, 0)
    seq.foldLeft(startingBase) {
      case (b, ba) => indexFromBucketedAttribute(original, b, tripletMapping, ba)
    }
  }

  def getEdgeDiagram(request: EdgeDiagramSpec): EdgeDiagramResponse = {
    val srcView = graph_operations.VertexView.fromDiagram(
      metaManager.scalar(request.srcDiagramId.asUUID))
    val dstView = graph_operations.VertexView.fromDiagram(
      metaManager.scalar(request.dstDiagramId.asUUID))
    val bundleWeights = getCompositeBundle(request.bundleSequence)
    val edgeBundle = bundleWeights.edgeBundle
    assert(srcView.vertexSet.gUID == edgeBundle.srcVertexSet.gUID,
      "Source vertex set does not match edge bundle source." +
        s"\nSource: ${srcView.vertexSet}\nEdge bundle source: ${edgeBundle.srcVertexSet}")
    assert(dstView.vertexSet.gUID == edgeBundle.dstVertexSet.gUID,
      "Destination vertex set does not match edge bundle destination." +
        s"\nSource: ${dstView.vertexSet}\nEdge bundle destination: ${edgeBundle.dstVertexSet}")
    val (srcTripletMapping, dstTripletMapping) = tripletMapping(edgeBundle)

    val srcFilteredEBs = srcView.filters
      .map(filteredAttribute =>
        filteredEdgesByAttribute(edgeBundle, srcTripletMapping, filteredAttribute))
    val dstFilteredEBs = dstView.filters
      .map(filteredAttribute =>
        filteredEdgesByAttribute(edgeBundle, dstTripletMapping, filteredAttribute))
    val allFilteredEBs = srcFilteredEBs ++ dstFilteredEBs
    val filteredEB = if (allFilteredEBs.size > 0) {
      val iop = graph_operations.EdgeBundleIntersection(allFilteredEBs.size)
      val builder = allFilteredEBs.zipWithIndex.foldLeft(iop.builder) {
        case (b, (eb, i)) => b(iop.ebs(i), eb)
      }
      builder.result.intersection.entity
    } else {
      edgeBundle
    }

    val srcIndices = indexFromIndexingSeq(
      edgeBundle, filteredEB, srcTripletMapping, srcView.indexingSeq)
    val dstIndices = indexFromIndexingSeq(
      edgeBundle, filteredEB, dstTripletMapping, dstView.indexingSeq)

    val cop = graph_operations.CountEdges()
    val originalEdgeCount = cop(cop.edges, edgeBundle).result.count
    val countOp = graph_operations.EdgeIndexPairCounter()
    val counts =
      countOp(
        countOp.xIndices, srcIndices)(
          countOp.yIndices, dstIndices)(
            countOp.original, edgeBundle)(
              countOp.originalCount, originalEdgeCount).result.counts.value
    EdgeDiagramResponse(
      request.srcDiagramId,
      request.dstDiagramId,
      request.srcIdx,
      request.dstIdx,
      counts.map { case ((s, d), c) => FEEdge(s, d, c) }.toSeq)
  }

  def getComplexView(request: FEGraphRequest): FEGraphResponse = {
    val vertexDiagrams = request.vertexSets.map(getVertexDiagram(_))
    val idxPattern = "idx\\[(\\d+)\\]".r
    def resolveDiagramId(reference: String): String = {
      reference match {
        case idxPattern(idx) => vertexDiagrams(idx.toInt).diagramId
        case id: String => id
      }
    }
    val modifiedEdgeSpecs = request.edgeBundles
      .map(eb => eb.copy(
        srcDiagramId = resolveDiagramId(eb.srcDiagramId),
        dstDiagramId = resolveDiagramId(eb.dstDiagramId)))
    val edgeDiagrams = modifiedEdgeSpecs.map(getEdgeDiagram(_))
    spark_util.Counters.printAll
    return FEGraphResponse(vertexDiagrams, edgeDiagrams)
  }

  private def getFilteredVS(
    attribute: Attribute[_], vertexFilters: Seq[FEVertexAttributeFilter]): VertexSet = {

    attribute match {
      case vertexAttribute: VertexAttribute[_] =>
        cacheVertexAttributes(vertexFilters.map(_.attributeId))
        FEFilters.filter(vertexAttribute.vertexSet, vertexFilters)
      case edgeAttribute: EdgeAttribute[_] => {
        val edgeBundle = edgeAttribute.edgeBundle
        val (srcTripletMapping, dstTripletMapping) = tripletMapping(edgeBundle)
        val filteredEBs = vertexFilters
          .map(_.toFilteredAttribute)
          .flatMap(filteredAttribute =>
            Iterator(
              filteredEdgesByAttribute(edgeBundle, srcTripletMapping, filteredAttribute),
              filteredEdgesByAttribute(edgeBundle, dstTripletMapping, filteredAttribute)))

        val filteredEB = if (filteredEBs.size > 0) {
          val iop = graph_operations.EdgeBundleIntersection(filteredEBs.size)
          iop(iop.ebs, filteredEBs).result.intersection.entity
        } else {
          edgeBundle
        }
        filteredEB.asVertexSet
      }
    }
  }

  def getCenter(request: CenterRequest): CenterResponse = {
    CenterResponse(Seq(0, 3)) // temporary constant response
  }

  def getHistogram(request: HistogramSpec): HistogramResponse = {
    val attribute = metaManager.attribute(request.attributeId.asUUID)
    val vertexAttribute = attribute match {
      case va: VertexAttribute[_] => va
      case ea: EdgeAttribute[_] => ea.asVertexAttribute
    }
    val bucketedAttr = FEBucketers.bucketedAttribute(
      metaManager, dataManager, vertexAttribute, request.numBuckets)
    val filteredVS = getFilteredVS(attribute, request.vertexFilters)
    val histogram = bucketedAttr.toHistogram(filteredVS)
    val counts = histogram.counts.value
    HistogramResponse(
      bucketedAttr.bucketer.labelType,
      bucketedAttr.bucketer.bucketLabels,
      (0 until bucketedAttr.bucketer.numBuckets).map(counts.getOrElse(_, 0)))
  }

  def getScalarValue(request: ScalarValueRequest): ScalarValueResponse = {
    val scalar = metaManager.scalar(request.scalarId.asUUID)
    ScalarValueResponse(scalar.value.toString)
  }
}
