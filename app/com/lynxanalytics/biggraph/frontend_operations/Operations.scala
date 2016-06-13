// "Frontend" operations are all defined here.
//
// The code in this file defines the operation parameters to be offered on the UI,
// and also takes care of parsing the parameters given by the user and creating
// the "backend" operations and updating the projects.
package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.graph_operations.EdgeBundleAsAttribute
import com.lynxanalytics.biggraph.graph_operations.RandomDistribution
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }
import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.graph_util.HadoopFile
import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.graph_util.Scripting._
import com.lynxanalytics.biggraph.controllers._
import com.lynxanalytics.biggraph.model
import com.lynxanalytics.biggraph.serving.FrontendJson
import com.lynxanalytics.biggraph.table.TableImport

import play.api.libs.json
import scala.reflect.runtime.universe.TypeTag

object OperationParams {
  case class Param(
      id: String,
      title: String,
      defaultValue: String = "",
      mandatory: Boolean = true) extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {}
  }

  case class Choice(
      id: String,
      title: String,
      options: List[FEOption],
      multipleChoice: Boolean = false,
      allowUnknownOption: Boolean = false,
      mandatory: Boolean = true) extends OperationParameterMeta {
    val kind = "choice"
    val defaultValue = options.headOption.map(_.id).getOrElse("")
    def validate(value: String): Unit = {
      if (!allowUnknownOption) {
        val possibleValues = options.map { x => x.id }.toSet
        val givenValues: Set[String] = if (!multipleChoice) Set(value) else {
          if (value.isEmpty) Set() else value.split(",", -1).toSet
        }
        val unknown = givenValues -- possibleValues
        assert(
          unknown.isEmpty,
          s"Unknown option: ${unknown.mkString(", ")}" +
            s" (Possibilities: ${possibleValues.mkString(", ")})")
      }
    }
  }

  case class TableParam(
      id: String,
      title: String,
      options: List[FEOption]) extends OperationParameterMeta {
    val kind = "table"
    val multipleChoice = false
    val defaultValue = ""
    val mandatory = true
    def validate(value: String): Unit = {}
  }

  case class TagList(
      id: String,
      title: String,
      options: List[FEOption],
      mandatory: Boolean = false) extends OperationParameterMeta {
    val kind = "tag-list"
    val multipleChoice = true
    val defaultValue = ""
    def validate(value: String): Unit = {}
  }

  case class File(id: String, title: String) extends OperationParameterMeta {
    val kind = "file"
    val multipleChoice = false
    val defaultValue = ""
    val options = List()
    val mandatory = true
    def validate(value: String): Unit = {}
  }

  case class Ratio(id: String, title: String, defaultValue: String = "")
      extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert((value matches """\d+(\.\d+)?""") && (value.toDouble <= 1.0),
        s"$title ($value) has to be a ratio, a double between 0.0 and 1.0")
    }
  }

  case class NonNegInt(id: String, title: String, default: Int)
      extends OperationParameterMeta {
    val kind = "default"
    val defaultValue = default.toString
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert(value matches """\d+""", s"$title ($value) has to be a non negative integer")
    }
  }

  case class NonNegDouble(id: String, title: String, defaultValue: String = "")
      extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert(value matches """\d+(\.\d+)?""", s"$title ($value) has to be a non negative double")
    }
  }

  case class Code(
      id: String,
      title: String,
      defaultValue: String = "",
      mandatory: Boolean = true) extends OperationParameterMeta {
    val kind = "code"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {}
  }

  // A random number to be used as default value for random seed parameters.
  case class RandomSeed(id: String, title: String) extends OperationParameterMeta {
    val defaultValue = util.Random.nextInt.toString
    val kind = "default"
    val options = List()
    val multipleChoice = false
    val mandatory = true
    def validate(value: String): Unit = {
      assert(value matches """[+-]?\d+""", s"$title ($value) has to be an integer")
    }
  }

  case class ModelParams(
      id: String,
      title: String,
      models: Map[String, model.ModelMeta],
      attrs: List[FEOption]) extends OperationParameterMeta {
    val defaultValue = ""
    val kind = "model"
    val multipleChoice = false
    val mandatory = true
    val options = List()
    import FrontendJson.wFEModelMeta
    import FrontendJson.wFEOption
    implicit val wModelsPayload = json.Json.writes[ModelsPayload]
    override val payload = Some(json.Json.toJson(ModelsPayload(
      models = models.toList.map { case (k, v) => model.Model.toMetaFE(k, v) },
      attrs = attrs)))
    def validate(value: String): Unit = {}
  }
}

// A special parameter payload to describe applicable models on the UI.
case class ModelsPayload(
  models: List[model.FEModelMeta],
  attrs: List[FEOption])

class Operations(env: SparkFreeEnvironment) extends OperationRepository(env) {
  import Operation.Category
  import Operation.Context
  abstract class UtilityOperation(t: String, c: Context)
    extends Operation(t, c, Category("Utility operations", "green", icon = "wrench", sortKey = "zz"))
  trait SegOp extends Operation {
    protected def seg = project.asSegmentation
    protected def parent = seg.parent
    protected def segmentationParameters(): List[OperationParameterMeta]
    def parameters = {
      if (project.isSegmentation) segmentationParameters
      else List[OperationParameterMeta]()
    }
  }
  abstract class SegmentationUtilityOperation(t: String, c: Context)
    extends Operation(t, c, Category(
      "Segmentation utility operations",
      "green",
      visible = c.project.isSegmentation,
      icon = "wrench",
      sortKey = "zz")) with SegOp

  // Categories
  abstract class SpecialtyOperation(t: String, c: Context)
    extends Operation(t, c, Category("Specialty operations", "green", icon = "book"))

  abstract class EdgeAttributesOperation(t: String, c: Context)
    extends Operation(t, c, Category("Edge attribute operations", "blue", sortKey = "Attribute, edge"))

  abstract class VertexAttributesOperation(t: String, c: Context)
    extends Operation(t, c, Category("Vertex attribute operations", "blue", sortKey = "Attribute, vertex"))

  abstract class GlobalOperation(t: String, c: Context)
    extends Operation(t, c, Category("Global operations", "magenta", icon = "globe"))

  abstract class ImportOperation(t: String, c: Context)
    extends Operation(t, c, Category("Import operations", "yellow", icon = "import"))

  abstract class MetricsOperation(t: String, c: Context)
    extends Operation(t, c, Category("Graph metrics", "green", icon = "stats"))

  abstract class PropagationOperation(t: String, c: Context)
    extends Operation(t, c, Category("Propagation operations", "green", icon = "fullscreen"))

  abstract class HiddenOperation(t: String, c: Context)
    extends Operation(t, c, Category("Hidden operations", "black", visible = false))

  abstract class DeprecatedOperation(t: String, c: Context)
    extends Operation(t, c, Category("Deprecated operations", "red", deprecated = true, icon = "remove-sign"))

  abstract class CreateSegmentationOperation(t: String, c: Context)
    extends Operation(t, c, Category(
      "Create segmentation",
      "green",
      icon = "th-large"))

  abstract class StructureOperation(t: String, c: Context)
    extends Operation(t, c, Category("Structure operations", "pink", icon = "asterisk"))

  import OperationParams._

  register("Discard vertices", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasVertexSet && isNotSegmentation
    def apply(params: Map[String, String]) = {
      project.vertexSet = null
    }
  })

  register("Discard edges", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      project.edgeBundle = null
    }
  })

  register("New vertex set", new StructureOperation(_, _) {
    def parameters = List(
      NonNegInt("size", "Vertex set size", default = 10))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val result = graph_operations.CreateVertexSet(params("size").toLong)().result
      project.setVertexSet(result.vs, idAttr = "id")
      project.newVertexAttribute("ordinal", result.ordinal)
    }
  })

  register("Create random edge bundle", new StructureOperation(_, _) {
    def parameters = List(
      NonNegDouble("degree", "Average degree", defaultValue = "10.0"),
      RandomSeed("seed", "Seed"))
    def enabled = hasVertexSet && hasNoEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.FastRandomEdgeBundle(
        params("seed").toInt, params("degree").toDouble)
      project.edgeBundle = op(op.vs, project.vertexSet).result.es
    }
  })

  register("Create scale-free random edge bundle", new StructureOperation(_, _) {
    def parameters = List(
      NonNegInt("iterations", "Number of iterations", default = 10),
      NonNegDouble(
        "perIterationMultiplier",
        "Per iteration edge number multiplier",
        defaultValue = "1.3"),
      RandomSeed("seed", "Seed"))
    def enabled = hasVertexSet && hasNoEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.ScaleFreeEdgeBundle(
        params("iterations").toInt,
        params("seed").toLong,
        params("perIterationMultiplier").toDouble)
      project.edgeBundle = op(op.vs, project.vertexSet).result.es
    }
  })

  register("Connect vertices on attribute", new StructureOperation(_, _) {
    def parameters = List(
      Choice("fromAttr", "Source attribute", options = vertexAttributes),
      Choice("toAttr", "Destination attribute", options = vertexAttributes))
    def enabled =
      (hasVertexSet && hasNoEdgeBundle
        && FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes."))
    private def applyAA[A](fromAttr: Attribute[A], toAttr: Attribute[A]) = {
      if (fromAttr == toAttr) {
        // Use the slightly faster operation.
        val op = graph_operations.EdgesFromAttributeMatches[A]()
        project.edgeBundle = op(op.attr, fromAttr).result.edges
      } else {
        val op = graph_operations.EdgesFromBipartiteAttributeMatches[A]()
        project.edgeBundle = op(op.fromAttr, fromAttr)(op.toAttr, toAttr).result.edges
      }
    }
    private def applyAB[A, B](fromAttr: Attribute[A], toAttr: Attribute[B]) = {
      applyAA(fromAttr, toAttr.asInstanceOf[Attribute[A]])
    }
    def apply(params: Map[String, String]) = {
      val fromAttrName = params("fromAttr")
      val toAttrName = params("toAttr")
      val fromAttr = project.vertexAttributes(fromAttrName)
      val toAttr = project.vertexAttributes(toAttrName)
      assert(fromAttr.typeTag.tpe =:= toAttr.typeTag.tpe,
        s"$fromAttrName and $toAttrName are not of the same type.")
      applyAB(fromAttr, toAttr)
    }
  })

  register("Import vertices", new ImportOperation(_, _) {
    def parameters = List(
      TableParam(
        "table",
        "Table to import from",
        accessibleTableOptions),
      Param("id-attr", "Save internal ID as", defaultValue = "id"))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val table = Table(TablePath.parse(params("table")), project.viewer)
      project.vertexSet = table.idSet
      for ((name, attr) <- table.columns) {
        project.newVertexAttribute(name, attr, "imported")
      }
      val idAttr = params("id-attr")
      if (idAttr.nonEmpty) {
        assert(
          !project.vertexAttributes.contains(idAttr),
          s"The input also contains a column called '$idAttr'. Please pick a different name.")
        project.newVertexAttribute(idAttr, project.vertexSet.idAttribute, "internal")
      }
    }
  })

  register("Import edges for existing vertices", new ImportOperation(_, _) {
    def parameters = List(
      TableParam(
        "table",
        "Table to import from",
        accessibleTableOptions),
      Choice("attr", "Vertex ID attribute", options = FEOption.unset +: vertexAttributes),
      Param("src", "Source ID column"),
      Param("dst", "Destination ID column"))
    def enabled =
      hasNoEdgeBundle &&
        hasVertexSet &&
        FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes to use as id.")
    def apply(params: Map[String, String]) = {
      val table = Table(TablePath.parse(params("table")), project.viewer)
      val src = params("src")
      val dst = params("dst")
      assert(src.nonEmpty, "The Source ID column parameter must be set.")
      assert(dst.nonEmpty, "The Destination ID column parameter must be set.")
      val attrName = params("attr")
      assert(attrName != FEOption.unset.id, "The Vertex ID attribute parameter must be set.")
      val attr = project.vertexAttributes(attrName)
      val imp = graph_operations.ImportEdgesForExistingVertices.runtimeSafe(
        attr, attr, table.column(src), table.column(dst))
      project.edgeBundle = imp.edges
      for ((name, attr) <- table.columns) {
        project.edgeAttributes(name) = attr.pullVia(imp.embedding)
      }
    }
  })

  register("Import vertices and edges from a single table", new ImportOperation(_, _) {
    def parameters = List(
      TableParam(
        "table",
        "Table to import from",
        accessibleTableOptions),
      Param("src", "Source ID column"),
      Param("dst", "Destination ID column"))
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val src = params("src")
      val dst = params("dst")
      assert(src.nonEmpty, "The Source ID column parameter must be set.")
      assert(dst.nonEmpty, "The Destination ID column parameter must be set.")
      val table = Table(TablePath.parse(params("table")), project.viewer)
      val eg = {
        val op = graph_operations.VerticesToEdges()
        op(op.srcAttr, table.column(src).runtimeSafeCast[String])(
          op.dstAttr, table.column(dst).runtimeSafeCast[String]).result
      }
      project.setVertexSet(eg.vs, idAttr = "id")
      project.newVertexAttribute("stringID", eg.stringID)
      project.edgeBundle = eg.es
      for ((name, attr) <- table.columns) {
        project.edgeAttributes(name) = attr.pullVia(eg.embedding)
      }
    }
  })

  register("Import vertex attributes", new ImportOperation(_, _) {
    def parameters = List(
      TableParam(
        "table",
        "Table to import from",
        accessibleTableOptions),
      Choice("id-attr", "Vertex ID attribute",
        options = FEOption.unset +: vertexAttributes[String]),
      Param("id-column", "ID column"),
      Param("prefix", "Name prefix for the imported vertex attributes"))
    def enabled =
      hasVertexSet &&
        FEStatus.assert(vertexAttributes[String].nonEmpty, "No vertex attributes to use as id.")
    def apply(params: Map[String, String]) = {
      val table = Table(TablePath.parse(params("table")), project.viewer)
      val attrName = params("id-attr")
      assert(attrName != FEOption.unset.id, "The Vertex ID attribute parameter must be set.")
      val idAttr = project.vertexAttributes(attrName).runtimeSafeCast[String]
      val idColumn = table.column(params("id-column")).runtimeSafeCast[String]
      val op = graph_operations.EdgesFromUniqueBipartiteAttributeMatches()
      val res = op(op.fromAttr, idAttr)(op.toAttr, idColumn).result
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- table.columns) {
        project.newVertexAttribute(prefix + name, attr.pullVia(res.edges), "imported")
      }
    }
  })

  register("Import edge attributes", new ImportOperation(_, _) {
    def parameters = List(
      TableParam(
        "table",
        "Table to import from",
        accessibleTableOptions),
      Choice("id-attr", "Edge ID attribute",
        options = FEOption.unset +: edgeAttributes[String]),
      Param("id-column", "ID column"),
      Param("prefix", "Name prefix for the imported edge attributes"))
    def enabled =
      hasEdgeBundle &&
        FEStatus.assert(edgeAttributes[String].nonEmpty, "No edge attributes to use as id.")
    def apply(params: Map[String, String]) = {
      val table = Table(TablePath.parse(params("table")), project.viewer)
      val columnName = params("id-column")
      assert(columnName.nonEmpty, "The ID column parameter must be set.")
      val attrName = params("id-attr")
      assert(attrName != FEOption.unset.id, "The Edge ID attribute parameter must be set.")
      val idAttr = project.edgeAttributes(attrName).runtimeSafeCast[String]
      val idColumn = table.column(params("id-column")).runtimeSafeCast[String]
      val op = graph_operations.EdgesFromUniqueBipartiteAttributeMatches()
      val res = op(op.fromAttr, idAttr)(op.toAttr, idColumn).result
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- table.columns) {
        project.newEdgeAttribute(prefix + name, attr.pullVia(res.edges), "imported")
      }
    }
  })

  register("Maximal cliques", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "maximal_cliques"),
      Choice(
        "bothdir", "Edges required in both directions", options = FEOption.bools),
      NonNegInt("min", "Minimum clique size", default = 3))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val minCliques = params("min").toInt
      val bothDir = params("bothdir").toBoolean
      val op = graph_operations.FindMaxCliques(minCliques, bothDir)
      val result = op(op.es, project.edgeBundle).result
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(result.segments, idAttr = "id")
      segmentation.notes =
        s"Maximal cliques (edges in both directions: $bothDir, minimum clique size: $minCliques)"
      segmentation.belongsTo = result.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
    }
  })

  register("Check cliques", new SegmentationUtilityOperation(_, _) {
    def segmentationParameters = List(
      Param("selected", "Segment IDs to check", defaultValue = "<All>"),
      Choice("bothdir", "Edges required in both directions", options = FEOption.bools))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      val selected =
        if (params("selected") == "<All>") None
        else Some(params("selected").split(",", -1).map(_.toLong).toSet)
      val op = graph_operations.CheckClique(selected, params("bothdir").toBoolean)
      val result = op(op.es, parent.edgeBundle)(op.belongsTo, seg.belongsTo).result
      parent.scalars("invalid_cliques") = result.invalid
    }
  })

  register("Connected components", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "connected_components"),
      Choice(
        "directions",
        "Edge direction",
        options = FEOption.list("ignore directions", "require both directions")))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val directions = params("directions")
      val symmetric = directions match {
        case "ignore directions" => project.edgeBundle.addReversed
        case "require both directions" => project.edgeBundle.makeSymmetric
      }
      val op = graph_operations.ConnectedComponents()
      val result = op(op.es, symmetric).result
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(result.segments, idAttr = "id")
      segmentation.notes = s"Connected components (edges: $directions)"
      segmentation.belongsTo = result.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
    }
  })

  register("Find infocom communities", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param(
        "cliques_name", "Name for maximal cliques segmentation", defaultValue = "maximal_cliques"),
      Param(
        "communities_name", "Name for communities segmentation", defaultValue = "communities"),
      Choice("bothdir", "Edges required in cliques in both directions", options = FEOption.bools),
      NonNegInt("min_cliques", "Minimum clique size", default = 3),
      Ratio("adjacency_threshold", "Adjacency threshold for clique overlaps", defaultValue = "0.6"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val minCliques = params("min_cliques").toInt
      val bothDir = params("bothdir").toBoolean
      val adjacencyThreshold = params("adjacency_threshold").toDouble

      val cliquesResult = {
        val op = graph_operations.FindMaxCliques(minCliques, bothDir)
        op(op.es, project.edgeBundle).result
      }

      val cliquesSegmentation = project.segmentation(params("cliques_name"))
      cliquesSegmentation.setVertexSet(cliquesResult.segments, idAttr = "id")
      cliquesSegmentation.notes =
        s"Maximal cliques (edges in both directions: $bothDir, minimum clique size: $minCliques)"
      cliquesSegmentation.belongsTo = cliquesResult.belongsTo
      cliquesSegmentation.newVertexAttribute("size", computeSegmentSizes(cliquesSegmentation))

      val cedges = {
        val op = graph_operations.InfocomOverlapForCC(adjacencyThreshold)
        op(op.belongsTo, cliquesResult.belongsTo).result.overlaps
      }

      val ccResult = {
        val op = graph_operations.ConnectedComponents()
        op(op.es, cedges).result
      }

      val weightedVertexToClique = cliquesResult.belongsTo.const(1.0)
      val weightedCliqueToCommunity = ccResult.belongsTo.const(1.0)

      val vertexToCommunity = {
        val op = graph_operations.ConcatenateBundles()
        op(
          op.edgesAB, cliquesResult.belongsTo)(
            op.edgesBC, ccResult.belongsTo)(
              op.weightsAB, weightedVertexToClique)(
                op.weightsBC, weightedCliqueToCommunity).result.edgesAC
      }

      val communitiesSegmentation = project.segmentation(params("communities_name"))
      communitiesSegmentation.setVertexSet(ccResult.segments, idAttr = "id")
      communitiesSegmentation.notes =
        s"Infocom communities (edges in both directions: $bothDir, minimum clique size:" +
          s" $minCliques, adjacency threshold: $adjacencyThreshold)"
      communitiesSegmentation.belongsTo = vertexToCommunity
      communitiesSegmentation.newVertexAttribute(
        "size", computeSegmentSizes(communitiesSegmentation))
    }
  })

  register("Modular clustering", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "modular_clusters"),
      Choice("weights", "Weight attribute", options =
        FEOption.noWeight +: edgeAttributes[Double], mandatory = false),
      Param(
        "max-iterations",
        "Maximum number of iterations to do",
        defaultValue = "30"),
      Param(
        "min-increment-per-iteration",
        "Minimal modularity increment in an iteration to keep going",
        defaultValue = "0.001"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val edgeBundle = project.edgeBundle
      val weightsName = params.getOrElse("weights", FEOption.noWeight.id)
      val weights =
        if (weightsName == FEOption.noWeight.id) edgeBundle.const(1.0)
        else project.edgeAttributes(weightsName).runtimeSafeCast[Double]
      val result = {
        val op = graph_operations.FindModularClusteringByTweaks(
          params("max-iterations").toInt, params("min-increment-per-iteration").toDouble)
        op(op.edges, edgeBundle)(op.weights, weights).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(result.clusters, idAttr = "id")
      segmentation.notes =
        if (weightsName == FEOption.noWeight.id) "Modular clustering"
        else s"Modular clustering by $weightsName"
      segmentation.belongsTo = result.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))

      val symmetricDirection = Direction("all edges", project.edgeBundle)
      val symmetricEdges = symmetricDirection.edgeBundle
      val symmetricWeights = symmetricDirection.pull(weights)
      val modularity = {
        val op = graph_operations.Modularity()
        op(op.edges, symmetricEdges)(op.weights, symmetricWeights)(op.belongsTo, result.belongsTo)
          .result.modularity
      }
      segmentation.scalars("modularity") = modularity
    }
  })

  register("Segment by double attribute", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "bucketing"),
      Choice("attr", "Attribute", options = vertexAttributes[Double]),
      NonNegDouble("interval-size", "Interval size"),
      Choice("overlap", "Overlap", options = FEOption.noyes))
    def enabled = FEStatus.assert(vertexAttributes[Double].nonEmpty, "No double vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val attrName = params("attr")
      val overlap = params("overlap") == "yes"
      s"Segmentation by $attrName" + (if (overlap) " with overlap" else "")
    }

    def apply(params: Map[String, String]) = {
      val attrName = params("attr")
      val attr = project.vertexAttributes(attrName).runtimeSafeCast[Double]
      val overlap = params("overlap") == "yes"
      val intervalSize = params("interval-size").toDouble
      val bucketing = {
        val op = graph_operations.DoubleBucketing(intervalSize, overlap)
        op(op.attr, attr).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(bucketing.segments, idAttr = "id")
      segmentation.notes = summary(params)
      segmentation.belongsTo = bucketing.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
      segmentation.newVertexAttribute("bottom", bucketing.bottom)
      segmentation.newVertexAttribute("top", bucketing.top)
    }
  })

  register("Segment by string attribute", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "bucketing"),
      Choice("attr", "Attribute", options = vertexAttributes[String]))
    def enabled = FEStatus.assert(vertexAttributes[String].nonEmpty, "No string vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val attrName = params("attr")
      s"Segmentation by $attrName"
    }

    def apply(params: Map[String, String]) = {
      val attrName = params("attr")
      val attr = project.vertexAttributes(attrName).runtimeSafeCast[String]
      val bucketing = {
        val op = graph_operations.StringBucketing()
        op(op.attr, attr).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(bucketing.segments, idAttr = "id")
      segmentation.notes = summary(params)
      segmentation.belongsTo = bucketing.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
      segmentation.newVertexAttribute(attrName, bucketing.label)
    }
  })

  register("Segment by interval", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "bucketing"),
      Choice("begin_attr", "Begin attribute", options = vertexAttributes[Double]),
      Choice("end_attr", "End attribute", options = vertexAttributes[Double]),
      NonNegDouble("interval_size", "Interval size"),
      Choice("overlap", "Overlap", options = FEOption.noyes))
    def enabled = FEStatus.assert(
      vertexAttributes[Double].size >= 2,
      "Less than two double vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val beginAttrName = params("begin_attr")
      val endAttrName = params("end_attr")
      val overlap = params("overlap") == "yes"
      s"Interval segmentation by $beginAttrName and $endAttrName" + (if (overlap) " with overlap" else "")
    }

    def apply(params: Map[String, String]) = {
      val beginAttrName = params("begin_attr")
      val endAttrName = params("end_attr")
      val beginAttr = project.vertexAttributes(beginAttrName).runtimeSafeCast[Double]
      val endAttr = project.vertexAttributes(endAttrName).runtimeSafeCast[Double]
      val overlap = params("overlap") == "yes"
      val intervalSize = params("interval_size").toDouble
      val bucketing = {
        val op = graph_operations.IntervalBucketing(intervalSize, overlap)
        op(op.beginAttr, beginAttr)(op.endAttr, endAttr).result
      }
      val segmentation = project.segmentation(params("name"))
      segmentation.setVertexSet(bucketing.segments, idAttr = "id")
      segmentation.notes = summary(params)
      segmentation.belongsTo = bucketing.belongsTo
      segmentation.newVertexAttribute("size", computeSegmentSizes(segmentation))
      segmentation.newVertexAttribute("bottom", bucketing.bottom)
      segmentation.newVertexAttribute("top", bucketing.top)
    }
  })

  register("Segment by event sequence", new CreateSegmentationOperation(_, _) {
    val SegmentationPrefix = "Segmentation: "
    val AttributePrefix = "Attribute: "
    val possibleLocations =
      project
        .segmentations
        .map { seg => FEOption.regular(SegmentationPrefix + seg.segmentationName) }
        .toList ++
        vertexAttributes[String].map { attr => FEOption.regular(AttributePrefix + attr.title) }

    def parameters = List(
      Param("name", "Target segmentation name"),
      Choice(
        "location",
        "Location",
        options = possibleLocations),
      Choice("time-attr", "Time attribute", options = vertexAttributes[Double]),
      Choice("algorithm", "Algorithm", options = List(
        FEOption("continuous", "Take continuous event sequences"),
        FEOption("with-gaps", "Allow gaps in event sequences"))),
      NonNegInt("sequence-length", "Sequence length", default = 2),
      NonNegDouble("time-window-step", "Time window step"),
      NonNegDouble("time-window-length", "Time window length")
    )

    def enabled =
      FEStatus.assert(project.isSegmentation, "Must be run on a segmentation") &&
        FEStatus.assert(
          possibleLocations.nonEmpty,
          "There must be a string attribute or a sub-segmentation to define event locations") &&
          FEStatus.assert(
            vertexAttributes[Double].nonEmpty,
            "There must be a double attribute to define event times")

    def apply(params: Map[String, String]) = {
      val timeAttrName = params("time-attr")
      val timeAttr = project.vertexAttributes(timeAttrName).runtimeSafeCast[Double]
      val locationAttr = params("location")
      val belongsToLocation =
        if (locationAttr.startsWith(SegmentationPrefix)) {
          project.existingSegmentation(locationAttr.substring(SegmentationPrefix.length)).belongsTo
        } else {
          val locationAttribute =
            project.vertexAttributes(locationAttr.substring(AttributePrefix.length)).runtimeSafeCast[String]
          val op = graph_operations.StringBucketing()
          op(op.attr, locationAttribute).result.belongsTo.entity
        }

      val cells = {
        val op = graph_operations.SegmentByEventSequence(
          params("algorithm"),
          params("sequence-length").toInt,
          params("time-window-step").toDouble,
          params("time-window-length").toDouble)
        op(op.personBelongsToEvent, project.asSegmentation.belongsTo)(
          op.eventTimeAttribute, timeAttr)(
            op.eventBelongsToLocation, belongsToLocation).result
      }
      val segmentation = project.asSegmentation.parent.segmentation(params("name"))
      segmentation.setVertexSet(cells.segments, idAttr = "id")
      segmentation.notes = summary(params)
      segmentation.belongsTo = cells.belongsTo
      segmentation.vertexAttributes("description") = cells.segmentDescription
      segmentation.vertexAttributes("size") = toDouble(cells.segmentSize)
    }
  })

  register("Combine segmentations", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "New segmentation name"),
      Choice("segmentations", "Segmentations", options = segmentations, multipleChoice = true))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val segmentations = params("segmentations").replace(",", ", ")
      s"Combination of $segmentations"
    }

    def apply(params: Map[String, String]) = {
      val segmentations =
        params("segmentations").split(",", -1).map(project.existingSegmentation(_))
      assert(segmentations.size >= 2, "Please select at least 2 segmentations to combine.")
      val result = project.segmentation(params("name"))
      // Start by copying the first segmentation.
      val first = segmentations.head
      result.vertexSet = first.vertexSet;
      result.notes = summary(params)
      result.belongsTo = first.belongsTo
      for ((name, attr) <- first.vertexAttributes) {
        result.newVertexAttribute(
          s"${first.segmentationName}_$name", attr)
      }
      // Then combine the other segmentations one by one.
      for (seg <- segmentations.tail) {
        val combination = {
          val op = graph_operations.CombineSegmentations()
          op(op.belongsTo1, result.belongsTo)(op.belongsTo2, seg.belongsTo).result
        }
        val attrs = result.vertexAttributes.toMap
        result.vertexSet = combination.segments
        result.belongsTo = combination.belongsTo
        for ((name, attr) <- attrs) {
          // These names are already prefixed.
          result.vertexAttributes(name) = attr.pullVia(combination.origin1)
        }
        for ((name, attr) <- seg.vertexAttributes) {
          // Add prefix for the new attributes.
          result.newVertexAttribute(
            s"${seg.segmentationName}_$name",
            attr.pullVia(combination.origin2))
        }
      }
      // Calculate sizes and ids at the end.
      result.newVertexAttribute("size", computeSegmentSizes(result))
      result.newVertexAttribute("id", result.vertexSet.idAttribute)
    }
  })

  register("Internal vertex ID as attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "id"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      project.newVertexAttribute(params("name"), project.vertexSet.idAttribute, help)
    }
  })

  register("Internal edge ID as attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "id"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      project.newEdgeAttribute(params("name"), project.edgeBundle.idSet.idAttribute, help)
    }
  })

  register("Add gaussian vertex attribute", new DeprecatedOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "random"),
      RandomSeed("seed", "Seed"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.AddRandomAttribute(params("seed").toInt, "Standard Normal")
      project.newVertexAttribute(
        params("name"), op(op.vs, project.vertexSet).result.attr, help)
    }
  })

  register("Add random vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "random"),
      Choice("dist", "Distribution", options = FEOption.list(RandomDistribution.getNames)),
      RandomSeed("seed", "Seed"))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.AddRandomAttribute(params("seed").toInt, params("dist"))
      project.newVertexAttribute(
        params("name"), op(op.vs, project.vertexSet).result.attr, help)
    }
  })

  register("Add random edge attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "random"),
      Choice("dist", "Distribution", options = FEOption.list(RandomDistribution.getNames)),
      RandomSeed("seed", "Seed"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.AddRandomAttribute(params("seed").toInt, params("dist"))
      project.newEdgeAttribute(
        params("name"), op(op.vs, project.edgeBundle.idSet).result.attr, help)
    }
  })

  register("Add constant edge attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "weight"),
      Param("value", "Value", defaultValue = "1"),
      Choice("type", "Type", options = FEOption.list("Double", "String")))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val res = {
        if (params("type") == "Double") {
          project.edgeBundle.const(params("value").toDouble)
        } else {
          project.edgeBundle.const(params("value"))
        }
      }
      project.edgeAttributes(params("name")) = res
    }
  })

  register("Add constant vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name"),
      Param("value", "Value", defaultValue = "1"),
      Choice("type", "Type", options = FEOption.list("Double", "String")))
    def enabled = hasVertexSet
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val value = params("value")
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = (params("type") == "Double"), value)
      project.newVertexAttribute(
        params("name"), op(op.vs, project.vertexSet).result.attr, s"constant $value")
    }
  })

  register("Fill with constant default value", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Vertex attribute", options = vertexAttributes[String] ++ vertexAttributes[Double]),
      Param("def", "Default value"))
    def enabled = FEStatus.assert(
      (vertexAttributes[String] ++ vertexAttributes[Double]).nonEmpty, "No vertex attributes.")
    override def title = "Fill vertex attribute with constant default value"
    def apply(params: Map[String, String]) = {
      val attr = project.vertexAttributes(params("attr"))
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = attr.is[Double], params("def"))
      val default = op(op.vs, project.vertexSet).result
      project.vertexAttributes(params("attr")) = unifyAttribute(attr, default.attr.entity)
    }
  })

  register("Fill edge attribute with constant default value", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Edge attribute", options = edgeAttributes[String] ++ edgeAttributes[Double]),
      Param("def", "Default value"))
    def enabled = FEStatus.assert(
      (edgeAttributes[String] ++ edgeAttributes[Double]).nonEmpty, "No edge attributes.")
    def apply(params: Map[String, String]) = {
      val attr = project.edgeAttributes(params("attr"))
      val op: graph_operations.AddConstantAttribute[_] =
        graph_operations.AddConstantAttribute.doubleOrString(
          isDouble = attr.is[Double], params("def"))
      val default = op(op.vs, project.edgeBundle.idSet).result
      project.edgeAttributes(params("attr")) = unifyAttribute(attr, default.attr.entity)
    }
  })

  register("Merge two attributes", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "New attribute name", defaultValue = ""),
      Choice("attr1", "Primary attribute", options = vertexAttributes),
      Choice("attr2", "Secondary attribute", options = vertexAttributes))
    def enabled = FEStatus.assert(
      vertexAttributes.size >= 2, "Not enough vertex attributes.")
    override def title = "Merge two vertex attributes"
    def apply(params: Map[String, String]) = {
      val name = params("name")
      assert(name.nonEmpty, "You must specify a name for the new attribute.")
      val attr1 = project.vertexAttributes(params("attr1"))
      val attr2 = project.vertexAttributes(params("attr2"))
      assert(attr1.typeTag.tpe =:= attr2.typeTag.tpe,
        "The two attributes must have the same type.")
      project.newVertexAttribute(name, unifyAttribute(attr1, attr2))
    }
  })

  register("Merge two edge attributes", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "New attribute name", defaultValue = ""),
      Choice("attr1", "Primary attribute", options = edgeAttributes),
      Choice("attr2", "Secondary attribute", options = edgeAttributes))
    def enabled = FEStatus.assert(
      edgeAttributes.size >= 2, "Not enough edge attributes.")
    def apply(params: Map[String, String]) = {
      val name = params("name")
      assert(name.nonEmpty, "You must specify a name for the new attribute.")
      val attr1 = project.edgeAttributes(params("attr1"))
      val attr2 = project.edgeAttributes(params("attr2"))
      assert(attr1.typeTag.tpe =:= attr2.typeTag.tpe,
        "The two attributes must have the same type.")
      project.edgeAttributes(name) = unifyAttribute(attr1, attr2)
    }
  })

  register("Reduce vertex attributes to two dimensions", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("first_dimension_name", "Save as"),
      Param("second_dimension_name", "Save as"),
      Choice("features", "Predictors", options = vertexAttributes[Double], multipleChoice = true))
    def enabled = FEStatus.assert(vertexAttributes[Double].nonEmpty, "No double vertex attributes.")

    def apply(params: Map[String, String]) = {
      assert(params("features").nonEmpty, "Please select at least one predictor.")
      val featureNames = params("features").split(",", -1).sorted
      val features = featureNames.map {
        name => project.vertexAttributes(name).runtimeSafeCast[Double]
      }
      val op = graph_operations.ReduceDimensions(features.size)
      val result = op(op.features, features).result
      project.newVertexAttribute(params("first_dimension_name"), result.attr1, help)
      project.newVertexAttribute(params("second_dimension_name"), result.attr2, help)
    }
  })

  register("Reverse edge direction", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.ReverseEdges()
      val res = op(op.esAB, project.edgeBundle).result
      project.pullBackEdges(
        project.edgeBundle,
        project.edgeAttributes.toIndexedSeq,
        res.esBA,
        res.injection)
    }
  })

  register("Add reversed edges", new StructureOperation(_, _) {
    def parameters = List(
      Param("distattr", "Distinguishing edge attribute", mandatory = false)
    )
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val addIsNewAttr = params.getOrElse("distattr", "").nonEmpty

      val rev = {
        val op = graph_operations.AddReversedEdges(addIsNewAttr)
        op(op.es, project.edgeBundle).result
      }

      project.pullBackEdges(
        project.edgeBundle,
        project.edgeAttributes.toIndexedSeq,
        rev.esPlus,
        rev.newToOriginal)
      if (addIsNewAttr) {
        project.edgeAttributes(params("distattr")) = rev.isNew
      }
    }
  })

  register("Find vertex coloring", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "color"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.Coloring()
      project.newVertexAttribute(
        params("name"), op(op.es, project.edgeBundle).result.coloring, help)
    }
  })

  register("Clustering coefficient", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "clustering_coefficient"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.ClusteringCoefficient()
      project.newVertexAttribute(
        params("name"), op(op.es, project.edgeBundle).result.clustering, help)
    }
  })

  register("Embeddedness", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "embeddedness"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.Embeddedness()
      project.edgeAttributes(params("name")) = op(op.es, project.edgeBundle).result.embeddedness
    }
  })

  register("Dispersion", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "dispersion"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val dispersion = {
        val op = graph_operations.Dispersion()
        op(op.es, project.edgeBundle).result.dispersion.entity
      }
      val embeddedness = {
        val op = graph_operations.Embeddedness()
        op(op.es, project.edgeBundle).result.embeddedness.entity
      }
      // http://arxiv.org/pdf/1310.6753v1.pdf
      val normalizedDispersion = {
        val op = graph_operations.DeriveJSDouble(
          JavaScript("Math.pow(disp, 0.61) / (emb + 5)"),
          Seq("disp", "emb"))
        op(op.attrs, graph_operations.VertexAttributeToJSValue.seq(
          dispersion, embeddedness)).result.attr.entity
      }
      // TODO: recursive dispersion
      project.edgeAttributes(params("name")) = dispersion
      project.edgeAttributes("normalized_" + params("name")) = normalizedDispersion
    }
  })

  register("Degree", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "degree"),
      Choice("direction", "Count", options = Direction.options))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val es = Direction(params("direction"), project.edgeBundle, reversed = true).edgeBundle
      val op = graph_operations.OutDegree()
      project.newVertexAttribute(
        params("name"), op(op.es, es).result.outDegree, params("direction") + help)
    }
  })

  register("PageRank", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "page_rank"),
      Choice("weights", "Weight attribute",
        options = FEOption.noWeight +: edgeAttributes[Double], mandatory = false),
      NonNegInt("iterations", "Number of iterations", default = 5),
      Ratio("damping", "Damping factor", defaultValue = "0.85"))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val op = graph_operations.PageRank(params("damping").toDouble, params("iterations").toInt)
      val weightsName = params.getOrElse("weights", FEOption.noWeight.id)
      val weights =
        if (weightsName == FEOption.noWeight.id) project.edgeBundle.const(1.0)
        else project.edgeAttributes(params("weights")).runtimeSafeCast[Double]
      project.newVertexAttribute(
        params("name"), op(op.es, project.edgeBundle)(op.weights, weights).result.pagerank, help)
    }
  })

  register("Shortest path", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "shortest_distance"),
      Choice("edge_distance", "Edge distance attribute",
        options = FEOption.unitDistances +: edgeAttributes[Double]),
      Choice("starting_distance", "Starting distance attribute", options = vertexAttributes[Double]),
      NonNegInt("iterations", "Maximum number of iterations", default = 10)
    )
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set an attribute name.")
      val startingDistanceAttr = params("starting_distance")
      val startingDistance = project
        .vertexAttributes(startingDistanceAttr)
        .runtimeSafeCast[Double]
      val op = graph_operations.ShortestPath(params("iterations").toInt)
      val edgeDistance =
        if (params("edge_distance") == FEOption.unitDistances.id) {
          project.edgeBundle.const(1.0)
        } else {
          project.edgeAttributes(params("edge_distance")).runtimeSafeCast[Double]
        }
      project.newVertexAttribute(
        params("name"),
        op(op.es, project.edgeBundle)(op.edgeDistance, edgeDistance)(op.startingDistance, startingDistance).result.distance, help)
    }
  })

  register("Centrality", new MetricsOperation(_, _) {
    def parameters = List(
      Param("name", "Attribute name", defaultValue = "centrality"),
      NonNegInt("maxDiameter", "Maximal diameter to check", default = 10),
      Choice("algorithm", "Centrality type",
        options = FEOption.list("Harmonic", "Lin", "Average distance")),
      NonNegInt("bits", "Precision", default = 8))
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val name = params("name")
      val algorithm = params("algorithm")
      assert(name.nonEmpty, "Please set an attribute name.")
      val op = graph_operations.HyperBallCentrality(
        params("maxDiameter").toInt, algorithm, params("bits").toInt)
      project.newVertexAttribute(
        name, op(op.es, project.edgeBundle).result.centrality, algorithm + help)
    }
  })

  register("Add rank attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("rankattr", "Rank attribute name", defaultValue = "ranking"),
      Choice("keyattr", "Key attribute name", options = vertexAttributes[Double]),
      Choice("order", "Order", options = FEOption.list("ascending", "descending")))

    def enabled = FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric (double) vertex attributes")
    def apply(params: Map[String, String]) = {
      val keyAttr = params("keyattr")
      val rankAttr = params("rankattr")
      val ascending = params("order") == "ascending"
      assert(keyAttr.nonEmpty, "Please set a key attribute name.")
      assert(rankAttr.nonEmpty, "Please set a name for the rank attribute")
      val op = graph_operations.AddRankingAttributeDouble(ascending)
      val sortKey = project.vertexAttributes(keyAttr).runtimeSafeCast[Double]
      project.newVertexAttribute(
        rankAttr, op(op.sortKey, sortKey).result.ordinal.asDouble, s"rank by $keyAttr" + help)
    }
  })

  register("Example Graph", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val g = graph_operations.ExampleGraph()().result
      project.vertexSet = g.vertices
      project.edgeBundle = g.edges
      for ((name, attr) <- g.vertexAttributes) {
        project.newVertexAttribute(name, attr)
      }
      project.newVertexAttribute("id", project.vertexSet.idAttribute)
      project.edgeAttributes = g.edgeAttributes.mapValues(_.entity)
      for ((name, s) <- g.scalars) {
        project.scalars(name) = s.entity
      }
    }
  })

  register("Enhanced Example Graph", new HiddenOperation(_, _) {
    def parameters = List()
    def enabled = hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val g = graph_operations.EnhancedExampleGraph()().result
      project.vertexSet = g.vertices
      project.edgeBundle = g.edges
      for ((name, attr) <- g.vertexAttributes) {
        project.newVertexAttribute(name, attr)
      }
      project.newVertexAttribute("id", project.vertexSet.idAttribute)
      project.edgeAttributes = g.edgeAttributes.mapValues(_.entity)
    }
  })

  private val toStringHelpText = "Converts the selected %s attributes to string type."
  register("Vertex attribute to string", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Vertex attribute", options = vertexAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes.")
    def apply(params: Map[String, String]) = {
      for (attr <- params("attr").split(",", -1)) {
        project.vertexAttributes(attr) = project.vertexAttributes(attr).asString
      }
    }
  })

  register("Edge attribute to string", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Choice("attr", "Edge attribute", options = edgeAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes.")
    def apply(params: Map[String, String]) = {
      for (attr <- params("attr").split(",", -1)) {
        project.edgeAttributes(attr) = project.edgeAttributes(attr).asString
      }
    }
  })

  private val toDoubleHelpText =
    """Converts the selected string typed %s attributes to double (double precision floating point
    number) type.
    """
  register("Vertex attribute to double", new VertexAttributesOperation(_, _) {
    val eligible = vertexAttributes[String] ++ vertexAttributes[Long]
    def parameters = List(
      Choice("attr", "Vertex attribute", options = eligible, multipleChoice = true))
    def enabled = FEStatus.assert(eligible.nonEmpty, "No eligible vertex attributes.")
    def apply(params: Map[String, String]) = {
      for (name <- params("attr").split(",", -1)) {
        val attr = project.vertexAttributes(name)
        project.vertexAttributes(name) = toDouble(attr)
      }
    }
  })

  register("Edge attribute to double", new EdgeAttributesOperation(_, _) {
    val eligible = edgeAttributes[String] ++ edgeAttributes[Long]
    def parameters = List(
      Choice("attr", "Edge attribute", options = eligible, multipleChoice = true))
    def enabled = FEStatus.assert(eligible.nonEmpty, "No eligible edge attributes.")
    def apply(params: Map[String, String]) = {
      for (name <- params("attr").split(",", -1)) {
        val attr = project.edgeAttributes(name)
        project.edgeAttributes(name) = toDouble(attr)
      }
    }
  })

  register("Vertex attributes to position", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("output", "Save as", defaultValue = "position"),
      Choice("x", "X or latitude", options = vertexAttributes[Double]),
      Choice("y", "Y or longitude", options = vertexAttributes[Double]))
    def enabled = FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes.")
    def apply(params: Map[String, String]) = {
      assert(params("output").nonEmpty, "Please set an attribute name.")
      val pos = {
        val op = graph_operations.JoinAttributes[Double, Double]()
        val x = project.vertexAttributes(params("x")).runtimeSafeCast[Double]
        val y = project.vertexAttributes(params("y")).runtimeSafeCast[Double]
        op(op.a, x)(op.b, y).result.attr
      }
      project.vertexAttributes(params("output")) = pos
    }
  })

  register("Edge graph", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val op = graph_operations.EdgeGraph()
      val g = op(op.es, project.edgeBundle).result
      project.setVertexSet(g.newVS, idAttr = "id")
      project.edgeBundle = g.newES
    }
  })

  def collectIdentifiers[T <: MetaGraphEntity](
    holder: StateMapHolder[T],
    expr: String,
    prefix: String = ""): IndexedSeq[(String, T)] = {
    holder.filter {
      case (name, _) => containsIdentifierJS(expr, prefix + name)
    }.toIndexedSeq
  }

  register("Derived vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("output", "Save as"),
      Choice("type", "Result type", options = FEOption.list("double", "string")),
      Code("expr", "Value", defaultValue = "1 + 1"))
    def enabled = hasVertexSet
    override def summary(params: Map[String, String]) = {
      val name = params("output")
      s"Derived vertex attribute ($name)"
    }
    def apply(params: Map[String, String]) = {
      assert(params("output").nonEmpty, "Please set an output attribute name.")
      val expr = params("expr")
      val vertexSet = project.vertexSet
      val namedAttributes = collectIdentifiers[Attribute[_]](project.vertexAttributes, expr)
      val namedScalars = collectIdentifiers[Scalar[_]](project.scalars, expr)

      val result = params("type") match {
        case "string" =>
          graph_operations.DeriveJS.deriveFromAttributes[String](expr, namedAttributes, vertexSet, namedScalars)
        case "double" =>
          graph_operations.DeriveJS.deriveFromAttributes[Double](expr, namedAttributes, vertexSet, namedScalars)
      }
      project.newVertexAttribute(params("output"), result, expr + help)
    }
  })

  register("Derived edge attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Param("output", "Save as"),
      Choice("type", "Result type", options = FEOption.list("double", "string")),
      Code("expr", "Value", defaultValue = "1 + 1"))
    def enabled = hasEdgeBundle
    override def summary(params: Map[String, String]) = {
      val name = params("output")
      s"Derived edge attribute ($name)"
    }
    def apply(params: Map[String, String]) = {
      val expr = params("expr")
      val edgeBundle = project.edgeBundle
      val idSet = project.edgeBundle.idSet
      val namedEdgeAttributes = collectIdentifiers[Attribute[_]](project.edgeAttributes, expr)
      val namedSrcVertexAttributes =
        collectIdentifiers[Attribute[_]](project.vertexAttributes, expr, "src$")
          .map {
            case (name, attr) =>
              "src$" + name -> graph_operations.VertexToEdgeAttribute.srcAttribute(attr, edgeBundle)
          }
      val namedScalars = collectIdentifiers[Scalar[_]](project.scalars, expr)
      val namedDstVertexAttributes =
        collectIdentifiers[Attribute[_]](project.vertexAttributes, expr, "dst$")
          .map {
            case (name, attr) =>
              "dst$" + name -> graph_operations.VertexToEdgeAttribute.dstAttribute(attr, edgeBundle)
          }

      val namedAttributes =
        namedEdgeAttributes ++ namedSrcVertexAttributes ++ namedDstVertexAttributes

      val result = params("type") match {
        case "string" =>
          graph_operations.DeriveJS.deriveFromAttributes[String](expr, namedAttributes, idSet, namedScalars)
        case "double" =>
          graph_operations.DeriveJS.deriveFromAttributes[Double](expr, namedAttributes, idSet, namedScalars)
      }
      project.edgeAttributes(params("output")) = result
    }
  })

  register("Derive scalar", new GlobalOperation(_, _) {
    def parameters = List(
      Param("output", "Save as"),
      Choice("type", "Result type", options = FEOption.list("double", "string")),
      Code("expr", "Value", defaultValue = "1 + 1"))
    def enabled = FEStatus.enabled
    override def summary(params: Map[String, String]) = {
      val name = params("output")
      s"Derive scalar ($name)"
    }
    def apply(params: Map[String, String]) = {
      val expr = params("expr")
      val namedScalars = collectIdentifiers[Scalar[_]](project.scalars, expr)
      val result = params("type") match {
        case "string" =>
          graph_operations.DeriveJSScalar.deriveFromScalars[String](expr, namedScalars)
        case "double" =>
          graph_operations.DeriveJSScalar.deriveFromScalars[Double](expr, namedScalars)
      }
      project.scalars(params("output")) = result.sc
    }
  })

  register("Predict vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Choice("label", "Attribute to predict", options = vertexAttributes[Double]),
      Choice("features", "Predictors", options = vertexAttributes[Double], multipleChoice = true),
      Choice("method", "Method", options = FEOption.list(
        "Linear regression", "Ridge regression", "Lasso", "Logistic regression", "Naive Bayes",
        "Decision tree", "Random forest", "Gradient-boosted trees")))
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val method = params("method").capitalize
      val label = params("label")
      s"$method for $label"
    }
    def apply(params: Map[String, String]) = {
      assert(params("features").nonEmpty, "Please select at least one predictor.")
      val featureNames = params("features").split(",", -1)
      val features = featureNames.map {
        name => project.vertexAttributes(name).runtimeSafeCast[Double]
      }
      val labelName = params("label")
      val label = project.vertexAttributes(labelName).runtimeSafeCast[Double]
      val method = params("method")
      val prediction = {
        val op = graph_operations.Regression(method, features.size)
        op(op.label, label)(op.features, features).result.prediction
      }
      project.newVertexAttribute(s"${labelName}_prediction", prediction, s"$method for $labelName")
    }
  })

  register("Train linear regression model", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Param("name", "The name of the model"),
      Choice("label", "Attribute to predict", options = vertexAttributes[Double]),
      Choice("features", "Predictors", options = vertexAttributes[Double], multipleChoice = true),
      Choice("method", "Method", options = FEOption.list(
        "Linear regression", "Ridge regression", "Lasso")))
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes.")
    override def summary(params: Map[String, String]) = {
      val method = params("method").capitalize
      val label = params("label")
      s"build a model using $method for $label"
    }
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set the name of the model.")
      assert(params("features").nonEmpty, "Please select at least one predictor.")
      val featureNames = params("features").split(",", -1).sorted
      val features = featureNames.map {
        name => project.vertexAttributes(name).runtimeSafeCast[Double]
      }
      val name = params("name")
      val labelName = params("label")
      val label = project.vertexAttributes(labelName).runtimeSafeCast[Double]
      val method = params("method")
      val model = {
        val op = graph_operations.RegressionModelTrainer(
          method, labelName, featureNames.toList)
        op(op.label, label)(op.features, features).result.model
      }
      project.scalars(name) = model
    }
  })

  register("Predict from model", new VertexAttributesOperation(_, _) {
    val models = project.viewer.models
    def parameters = List(
      Param("name", "The name of the attribute of the predictions"),
      ModelParams("model", "The parameters of the model", models, vertexAttributes[Double]))
    def enabled =
      FEStatus.assert(models.nonEmpty, "No models.") &&
        FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes.")
    def apply(params: Map[String, String]) = {
      assert(params("name").nonEmpty, "Please set the name of attribute.")
      assert(params("model").nonEmpty, "Please select a model.")
      val name = params("name")
      val p = json.Json.parse(params("model"))
      val modelValue = project.scalars((p \ "modelName").as[String]).runtimeSafeCast[model.Model]
      val features = (p \ "features").as[List[String]].map {
        name => project.vertexAttributes(name).runtimeSafeCast[Double]
      }
      val predictedAttribute = {
        val op = graph_operations.PredictFromModel(features.size)
        op(op.model, modelValue)(op.features, features).result.prediction
      }
      project.newVertexAttribute(name, predictedAttribute, s"predicted from ${modelValue.name}")
    }
  })

  register("Aggregate to segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = aggregateParams(parent.vertexAttributes)
    def enabled =
      isSegmentation &&
        FEStatus.assert(parent.vertexAttributes.nonEmpty,
          "No vertex attributes on parent")
    def apply(params: Map[String, String]) = {
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo,
          AttributeWithLocalAggregator(parent.vertexAttributes(attr), choice))
        project.newVertexAttribute(s"${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate to segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Choice("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(parent.vertexAttributes, weighted = true)
    def enabled =
      isSegmentation &&
        FEStatus.assert(parent.vertexAttributeNames[Double].nonEmpty,
          "No numeric vertex attributes on parent")
    def apply(params: Map[String, String]) = {
      val weightName = params("weight")
      val weight = parent.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo,
          AttributeWithWeightedAggregator(weight, parent.vertexAttributes(attr), choice))
        project.newVertexAttribute(s"${attr}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("Aggregate from segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Generated name prefix",
        defaultValue = project.asSegmentation.segmentationName)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      isSegmentation &&
        FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo.reverse,
          AttributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        seg.parent.newVertexAttribute(s"${prefix}${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate from segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Generated name prefix",
        defaultValue = project.asSegmentation.segmentationName),
      Choice("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(project.vertexAttributes, weighted = true)
    def enabled =
      isSegmentation &&
        FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          seg.belongsTo.reverse,
          AttributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
        seg.parent.newVertexAttribute(s"${prefix}${attr}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("Create edges from set overlaps", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List(
      NonNegInt("minOverlap", "Minimal overlap for connecting two segments", default = 3))
    def enabled = hasNoEdgeBundle && isSegmentation
    def apply(params: Map[String, String]) = {
      val op = graph_operations.SetOverlap(params("minOverlap").toInt)
      val res = op(op.belongsTo, seg.belongsTo).result
      project.edgeBundle = res.overlaps
      // Long is better supported on the frontend than Int.
      project.edgeAttributes("Overlap size") = res.overlapSize.asLong
    }
  })

  private def segmentationSizesSquareSum(seg: SegmentationEditor, parent: ProjectEditor)(
    implicit manager: MetaGraphManager): Scalar[_] = {
    val size = aggregateViaConnection(
      seg.belongsTo,
      AttributeWithLocalAggregator(parent.vertexSet.idAttribute, "count")
    )
    val sizeSquare: Attribute[Double] = {
      val op = graph_operations.DeriveJSDouble(
        JavaScript("size * size"),
        Seq("size"))
      op(
        op.attrs,
        graph_operations.VertexAttributeToJSValue.seq(size)).result.attr
    }
    aggregate(AttributeWithAggregator(sizeSquare, "sum"))
  }

  register("Create edges from co-occurrence", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List()
    override def visibleScalars =
      if (project.isSegmentation) {
        val scalar = segmentationSizesSquareSum(seg, parent)
        implicit val entityProgressManager = env.entityProgressManager
        List(ProjectViewer.feScalar(scalar, "num_created_edges", ""))
      } else {
        List()
      }

    def enabled =
      isSegmentation &&
        FEStatus.assert(parent.edgeBundle == null, "Parent graph has edges already.")
    def apply(params: Map[String, String]) = {
      val op = graph_operations.EdgesFromSegmentation()
      val result = op(op.belongsTo, seg.belongsTo).result
      parent.edgeBundle = result.es
      for ((name, attr) <- project.vertexAttributes) {
        parent.edgeAttributes(s"${seg.segmentationName}_$name") = attr.pullVia(result.origin)
      }
    }
  })

  register("Sample edges from co-occurrence", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List(
      NonNegDouble("probability", "Vertex pair selection probability", defaultValue = "0.001"),
      RandomSeed("seed", "Random seed")
    )
    override def visibleScalars =
      if (project.isSegmentation) {
        val scalar = segmentationSizesSquareSum(seg, parent)
        implicit val entityProgressManager = env.entityProgressManager
        List(ProjectViewer.feScalar(scalar, "num_total_edges", ""))
      } else {
        List()
      }

    def enabled =
      isSegmentation &&
        FEStatus.assert(parent.edgeBundle == null, "Parent graph has edges already.")
    def apply(params: Map[String, String]) = {
      val op = graph_operations.SampleEdgesFromSegmentation(
        params("probability").toDouble,
        params("seed").toLong)
      val result = op(op.belongsTo, seg.belongsTo).result
      parent.edgeBundle = result.es
      parent.edgeAttributes("multiplicity") = result.multiplicity
    }
  })

  register("Pull segmentation one level up", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List()

    def enabled =
      isSegmentation && FEStatus.assert(parent.isSegmentation, "Parent graph is not a segmentation")

    def apply(params: Map[String, String]) = {
      val parentSegmentation = parent.asSegmentation
      val thisSegmentation = project.asSegmentation
      val segmentationName = thisSegmentation.segmentationName
      val targetSegmentation = parentSegmentation.parent.segmentation(segmentationName)
      targetSegmentation.state = thisSegmentation.state
      targetSegmentation.belongsTo =
        parentSegmentation.belongsTo.concat(thisSegmentation.belongsTo)
    }
  })

  register("Aggregate on neighbors", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Choice("direction", "Aggregate on", options = Direction.options)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes") && hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = Direction(params("direction"), project.edgeBundle).edgeBundle
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          edges,
          AttributeWithLocalAggregator(project.vertexAttributes(attr), choice))
        project.newVertexAttribute(s"${prefix}${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate on neighbors", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "neighborhood"),
      Choice("weight", "Weight", options = vertexAttributes[Double]),
      Choice("direction", "Aggregate on", options = Direction.options)) ++
      aggregateParams(project.vertexAttributes, weighted = true)
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes") && hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val edges = Direction(params("direction"), project.edgeBundle).edgeBundle
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((name, choice) <- parseAggregateParams(params)) {
        val attr = project.vertexAttributes(name)
        val result = aggregateViaConnection(
          edges,
          AttributeWithWeightedAggregator(weight, attr, choice))
        project.newVertexAttribute(s"${prefix}${name}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("Split vertices", new StructureOperation(_, _) {
    def parameters = List(
      Choice("rep", "Repetition attribute", options = vertexAttributes[Double]),
      Param("idattr", "ID attribute name", defaultValue = "new_id"),
      Param("idx", "Index attribute name", defaultValue = "index"))

    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No double vertex attributes")
    def doSplit(doubleAttr: Attribute[Double]): graph_operations.SplitVertices.Output = {
      val op = graph_operations.SplitVertices()
      op(op.attr, doubleAttr.asLong).result
    }
    def apply(params: Map[String, String]) = {
      val rep = params("rep")

      val split = doSplit(project.vertexAttributes(rep).runtimeSafeCast[Double])

      project.pullBack(split.belongsTo)
      project.vertexAttributes(params("idx")) = split.indexAttr
      project.newVertexAttribute(params("idattr"), project.vertexSet.idAttribute)
    }
  })

  register("Merge vertices by attribute", new StructureOperation(_, _) {
    def parameters = List(
      Choice("key", "Match by", options = vertexAttributes)) ++
      aggregateParams(project.vertexAttributes)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def merge[T](attr: Attribute[T]): graph_operations.MergeVertices.Output = {
      val op = graph_operations.MergeVertices[T]()
      op(op.attr, attr).result
    }
    def apply(params: Map[String, String]) = {
      val key = params("key")
      val m = merge(project.vertexAttributes(key))
      val oldVAttrs = project.vertexAttributes.toMap
      val oldEdges = project.edgeBundle
      val oldEAttrs = project.edgeAttributes.toMap
      val oldSegmentations = project.viewer.segmentationMap
      project.setVertexSet(m.segments, idAttr = "id")
      for ((name, segViewer) <- oldSegmentations) {
        val seg = project.segmentation(name)
        seg.segmentationState = segViewer.segmentationState
        val op = graph_operations.InducedEdgeBundle(induceDst = false)
        seg.belongsTo = op(
          op.srcMapping, m.belongsTo)(
            op.edges, seg.belongsTo).result.induced
      }
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateViaConnection(
          m.belongsTo,
          AttributeWithLocalAggregator(oldVAttrs(attr), choice))
        project.newVertexAttribute(s"${attr}_${choice}", result)
      }
      // Automatically keep the key attribute.
      project.vertexAttributes(key) = aggregateViaConnection(
        m.belongsTo,
        AttributeWithLocalAggregator(oldVAttrs(key), "most_common"))
      if (oldEdges != null) {
        val edgeInduction = {
          val op = graph_operations.InducedEdgeBundle()
          op(op.srcMapping, m.belongsTo)(op.dstMapping, m.belongsTo)(op.edges, oldEdges).result
        }
        project.edgeBundle = edgeInduction.induced
        for ((name, eAttr) <- oldEAttrs) {
          project.edgeAttributes(name) = eAttr.pullVia(edgeInduction.embedding)
        }
      }
    }
  })

  private def mergeEdgesWithKey[T](edgesAsAttr: Attribute[(ID, ID)], keyAttr: Attribute[T]) = {
    val edgesAndKey: Attribute[((ID, ID), T)] = edgesAsAttr.join(keyAttr)
    val op = graph_operations.MergeVertices[((ID, ID), T)]()
    op(op.attr, edgesAndKey).result
  }

  private def mergeEdges(edgesAsAttr: Attribute[(ID, ID)]) = {
    val op = graph_operations.MergeVertices[(ID, ID)]()
    op(op.attr, edgesAsAttr).result
  }
  // Common code for operations "merge parallel edges" and "merge parallel edges by key"

  private def applyMergeParallelEdgesByKey(project: ProjectEditor, params: Map[String, String]) = {

    val edgesAsAttr = {
      val op = graph_operations.EdgeBundleAsAttribute()
      op(op.edges, project.edgeBundle).result.attr
    }

    val hasKeyAttr = params.contains("key")

    val mergedResult =
      if (hasKeyAttr) {
        val keyAttr = project.edgeAttributes(params("key"))
        mergeEdgesWithKey(edgesAsAttr, keyAttr)
      } else {
        mergeEdges(edgesAsAttr)
      }

    val newEdges = {
      val op = graph_operations.PulledOverEdges()
      op(op.originalEB, project.edgeBundle)(op.injection, mergedResult.representative)
        .result.pulledEB
    }
    val oldAttrs = project.edgeAttributes.toMap
    project.edgeBundle = newEdges

    for ((attr, choice) <- parseAggregateParams(params)) {
      project.edgeAttributes(s"${attr}_${choice}") =
        aggregateViaConnection(
          mergedResult.belongsTo,
          AttributeWithLocalAggregator(oldAttrs(attr), choice))
    }
    if (hasKeyAttr) {
      val key = params("key")
      project.edgeAttributes(key) =
        aggregateViaConnection(mergedResult.belongsTo,
          AttributeWithLocalAggregator(oldAttrs(key), "most_common"))
    }
  }

  register("Merge parallel edges", new StructureOperation(_, _) {
    def parameters = aggregateParams(project.edgeAttributes)
    def enabled = hasEdgeBundle

    def apply(params: Map[String, String]) = {
      applyMergeParallelEdgesByKey(project, params)
    }
  })

  register("Merge parallel edges by attribute", new StructureOperation(_, _) {
    def parameters = List(
      Choice("key", "Merge by", options = edgeAttributes)) ++
      aggregateParams(project.edgeAttributes)
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty,
      "There must be at least one edge attribute")

    def apply(params: Map[String, String]) = {
      applyMergeParallelEdgesByKey(project, params)
    }
  })

  register("Discard loop edges", new StructureOperation(_, _) {
    def parameters = List()
    def enabled = hasEdgeBundle
    def apply(params: Map[String, String]) = {
      val edgesAsAttr = {
        val op = graph_operations.EdgeBundleAsAttribute()
        op(op.edges, project.edgeBundle).result.attr
      }
      val guid = edgesAsAttr.entity.gUID.toString
      val embedding = FEFilters.embedFilteredVertices(
        project.edgeBundle.idSet,
        Seq(FEVertexAttributeFilter(guid, "!=")))
      project.pullBackEdges(embedding)
    }
  })

  register("Aggregate vertex attribute globally", new GlobalOperation(_, _) {
    def parameters = List(Param("prefix", "Generated name prefix")) ++
      aggregateParams(project.vertexAttributes, needsGlobal = true)
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(AttributeWithAggregator(project.vertexAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}"
        project.scalars(name) = result
      }
    }
  })

  register("Weighted aggregate vertex attribute globally", new GlobalOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix"),
      Choice("weight", "Weight", options = vertexAttributes[Double])) ++
      aggregateParams(project.vertexAttributes, needsGlobal = true, weighted = true)
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.vertexAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          AttributeWithWeightedAggregator(weight, project.vertexAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}_by_${weightName}"
        project.scalars(name) = result
      }
    }
  })

  register("Aggregate edge attribute globally", new GlobalOperation(_, _) {
    def parameters = List(Param("prefix", "Generated name prefix")) ++
      aggregateParams(
        project.edgeAttributes,
        needsGlobal = true)
    def enabled =
      FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          AttributeWithAggregator(project.edgeAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}"
        project.scalars(name) = result
      }
    }
  })

  register("Weighted aggregate edge attribute globally", new GlobalOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix"),
      Choice("weight", "Weight", options = edgeAttributes[Double])) ++
      aggregateParams(
        project.edgeAttributes,
        needsGlobal = true, weighted = true)
    def enabled =
      FEStatus.assert(edgeAttributes[Double].nonEmpty, "No numeric edge attributes")
    def apply(params: Map[String, String]) = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.edgeAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregate(
          AttributeWithWeightedAggregator(weight, project.edgeAttributes(attr), choice))
        val name = s"${prefix}${attr}_${choice}_by_${weightName}"
        project.scalars(name) = result
      }
    }
  })

  register("Aggregate edge attribute to vertices", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "edge"),
      Choice("direction", "Aggregate on", options = Direction.attrOptions)) ++
      aggregateParams(
        project.edgeAttributes)
    def enabled =
      FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    def apply(params: Map[String, String]) = {
      val direction = Direction(params("direction"), project.edgeBundle)
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateFromEdges(
          direction.edgeBundle,
          AttributeWithLocalAggregator(
            direction.pull(project.edgeAttributes(attr)),
            choice))
        project.newVertexAttribute(s"${prefix}${attr}_${choice}", result)
      }
    }
  })

  register("Weighted aggregate edge attribute to vertices", new PropagationOperation(_, _) {
    def parameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "edge"),
      Choice("weight", "Weight", options = edgeAttributes[Double]),
      Choice("direction", "Aggregate on", options = Direction.attrOptions)) ++
      aggregateParams(
        project.edgeAttributes,
        weighted = true)
    def enabled =
      FEStatus.assert(edgeAttributes[Double].nonEmpty, "No numeric edge attributes")
    def apply(params: Map[String, String]) = {
      val direction = Direction(params("direction"), project.edgeBundle)
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      val weightName = params("weight")
      val weight = project.edgeAttributes(weightName).runtimeSafeCast[Double]
      for ((attr, choice) <- parseAggregateParams(params)) {
        val result = aggregateFromEdges(
          direction.edgeBundle,
          AttributeWithWeightedAggregator(
            direction.pull(weight),
            direction.pull(project.edgeAttributes(attr)),
            choice))
        project.newVertexAttribute(s"${prefix}${attr}_${choice}_by_${weightName}", result)
      }
    }
  })

  register("No operation", new UtilityOperation(_, _) {
    def parameters = List()
    def enabled = FEStatus.enabled
    def apply(params: Map[String, String]) = {}
  })

  register("Discard edge attribute", new EdgeAttributesOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = edgeAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    override def title = "Discard edge attributes"
    override def summary(params: Map[String, String]) = {
      val names = params("name").replace(",", ", ")
      s"Discard edge attributes: $names"
    }
    def apply(params: Map[String, String]) = {
      for (param <- params("name").split(",", -1)) {
        project.deleteEdgeAttribute(param)
      }
    }
  })

  register("Discard vertex attribute", new VertexAttributesOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = vertexAttributes, multipleChoice = true))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    override def title = "Discard vertex attributes"
    override def summary(params: Map[String, String]) = {
      val names = params("name").replace(",", ", ")
      s"Discard vertex attributes: $names"
    }
    def apply(params: Map[String, String]) = {
      for (param <- params("name").split(",", -1)) {
        project.deleteVertexAttribute(param)
      }
    }
  })

  register("Discard segmentation", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = segmentations))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val name = params("name")
      s"Discard segmentation: $name"
    }
    def apply(params: Map[String, String]) = {
      project.deleteSegmentation(params("name"))
    }
  })

  register("Discard scalar", new GlobalOperation(_, _) {
    def parameters = List(
      Choice("name", "Name", options = scalars, multipleChoice = true))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    override def title = "Discard scalars"
    override def summary(params: Map[String, String]) = {
      val names = params("name").replace(",", ", ")
      s"Discard scalars: $names"
    }
    def apply(params: Map[String, String]) = {
      for (param <- params("name").split(",", -1)) {
        project.deleteScalar(param)
      }
    }
  })

  register("Rename edge attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = edgeAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename edge attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(!project.edgeAttributes.contains(params("to")),
        s"""An edge-attribute named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.edgeAttributes(params("to")) = project.edgeAttributes(params("from"))
      project.edgeAttributes(params("from")) = null
    }
  })

  register("Rename vertex attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = vertexAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename vertex attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(!project.vertexAttributes.contains(params("to")),
        s"""A vertex-attribute named '${params("to")}' already exists,
            please discard it or choose another name""")
      assert(params("to").nonEmpty, "Please set the new attribute name.")
      project.newVertexAttribute(
        params("to"), project.vertexAttributes(params("from")),
        project.viewer.getVertexAttributeNote(params("from")))
      project.vertexAttributes(params("from")) = null
    }
  })

  register("Rename segmentation", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = segmentations),
      Param("to", "New name"))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename segmentation $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(
        !project.segmentationNames.contains(params("to")),
        s"""A segmentation named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.segmentation(params("to")).segmentationState =
        project.existingSegmentation(params("from")).segmentationState
      project.deleteSegmentation(params("from"))
    }
  })

  register("Rename scalar", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = scalars),
      Param("to", "New name"))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Rename scalar $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(!project.scalars.contains(params("to")),
        s"""A scalar named '${params("to")}' already exists,
            please discard it or choose another name""")
      project.scalars(params("to")) = project.scalars(params("from"))
      project.scalars(params("from")) = null
    }
  })

  register("Copy edge attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = edgeAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy edge attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      project.edgeAttributes(params("to")) = project.edgeAttributes(params("from"))
    }
  })

  register("Copy vertex attribute", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = vertexAttributes),
      Param("to", "New name"))
    def enabled = FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy vertex attribute $from to $to"
    }
    def apply(params: Map[String, String]) = {
      assert(params("to").nonEmpty, "Please set the new attribute name.")
      project.newVertexAttribute(
        params("to"), project.vertexAttributes(params("from")),
        project.viewer.getVertexAttributeNote(params("from")))
    }
  })

  register("Copy segmentation", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = segmentations),
      Param("to", "New name"))
    def enabled = FEStatus.assert(segmentations.nonEmpty, "No segmentations")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy segmentation $from to $to"
    }
    def apply(params: Map[String, String]) = {
      val from = project.existingSegmentation(params("from"))
      val to = project.segmentation(params("to"))
      to.segmentationState = from.segmentationState
    }
  })

  register("Copy scalar", new UtilityOperation(_, _) {
    def parameters = List(
      Choice("from", "Old name", options = scalars),
      Param("to", "New name"))
    def enabled = FEStatus.assert(scalars.nonEmpty, "No scalars")
    override def summary(params: Map[String, String]) = {
      val from = params("from")
      val to = params("to")
      s"Copy scalar $from to $to"
    }
    def apply(params: Map[String, String]) = {
      project.scalars(params("to")) = project.scalars(params("from"))
    }
  })

  register("Copy graph into a segmentation", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Param("name", "Segmentation name", defaultValue = "self_as_segmentation"))
    def enabled = hasVertexSet

    def apply(params: Map[String, String]) = {
      val oldProjectState = project.state
      val segmentation = project.segmentation(params("name"))
      segmentation.state = oldProjectState
      for (subSegmentationName <- segmentation.segmentationNames) {
        segmentation.deleteSegmentation(subSegmentationName)
      }

      val op = graph_operations.LoopEdgeBundle()
      segmentation.belongsTo = op(op.vs, project.vertexSet).result.eb
    }
  })

  register("Import project as segmentation", new CreateSegmentationOperation(_, _) {
    def parameters = List(
      Choice(
        "them",
        "Other project's name",
        options = readableProjectCheckpoints,
        allowUnknownOption = true))
    def enabled = hasVertexSet
    override def summary(params: Map[String, String]) = {
      val (cp, title, suffix) = FEOption.unpackTitledCheckpoint(params("them"))
      s"Import $title as segmentation"
    }
    def apply(params: Map[String, String]) = {
      val themId = params("them")
      val (cp, title, suffix) = FEOption.unpackTitledCheckpoint(
        themId,
        customError =
          s"Obsolete project reference: $themId. Please select a new project from the dropdown.")
      assert(suffix == "", s"Invalid project reference $themId with suffix $suffix")
      val baseName = SymbolPath.parse(title).last.name
      val them = new RootProjectViewer(manager.checkpointRepo.readCheckpoint(cp))
      assert(them.vertexSet != null, s"No vertex set in $them")
      val segmentation = project.segmentation(baseName)
      segmentation.state = them.state
      val op = graph_operations.EmptyEdgeBundle()
      segmentation.belongsTo = op(op.src, project.vertexSet)(op.dst, them.vertexSet).result.eb
    }
  })

  register("Import segmentation links", new ImportOperation(_, _) with SegOp {
    def segmentationParameters = List(
      TableParam(
        "table",
        "Table to import from",
        accessibleTableOptions),
      Choice(
        "base-id-attr",
        s"Identifying vertex attribute in base project",
        options = FEOption.list(parent.vertexAttributeNames.toList)),
      Param("base-id-column", s"Identifying column for base project"),
      Choice(
        "seg-id-attr",
        s"Identifying vertex attribute in segmentation",
        options = vertexAttributes),
      Param("seg-id-column", s"Identifying column for segmentation"))
    def enabled =
      isSegmentation &&
        FEStatus.assert(
          vertexAttributes.nonEmpty,
          "No vertex attributes in this segmentation") &&
          FEStatus.assert(
            parent.vertexAttributeNames.nonEmpty,
            "No vertex attributes in base project")
    def apply(params: Map[String, String]) = {
      val table = Table(TablePath.parse(params("table")), project.viewer)
      val baseColumnName = params("base-id-column")
      val segColumnName = params("seg-id-column")
      val baseAttrName = params("base-id-attr")
      val segAttrName = params("seg-id-attr")
      assert(baseColumnName.nonEmpty,
        "The identifying column parameter must be set for the base project.")
      assert(segColumnName.nonEmpty,
        "The identifying column parameter must be set for the segmentation.")
      assert(baseAttrName != FEOption.unset.id,
        "The base ID attribute parameter must be set.")
      assert(segAttrName != FEOption.unset.id,
        "The segmentation ID attribute parameter must be set.")
      val imp = graph_operations.ImportEdgesForExistingVertices.runtimeSafe(
        parent.vertexAttributes(baseAttrName),
        project.vertexAttributes(segAttrName),
        table.column(baseColumnName),
        table.column(segColumnName))
      seg.belongsTo = imp.edges
    }
  })

  register("Import segmentation", new ImportOperation(_, _) {
    def parameters = List(
      TableParam(
        "table",
        "Table to import from",
        accessibleTableOptions),
      Param("name", s"Name of new segmentation"),
      Choice("base-id-attr", "Vertex ID attribute",
        options = FEOption.unset +: vertexAttributes),
      Param("base-id-column", "Vertex ID column"),
      Param("seg-id-column", "Segment ID column"))
    def enabled = FEStatus.assert(
      vertexAttributes.nonEmpty, "No suitable vertex attributes")
    def apply(params: Map[String, String]) = {
      val table = Table(TablePath.parse(params("table")), project.viewer)
      val baseColumnName = params("base-id-column")
      val segColumnName = params("seg-id-column")
      val baseAttrName = params("base-id-attr")
      assert(baseColumnName.nonEmpty,
        "The identifying column parameter must be set for the base project.")
      assert(segColumnName.nonEmpty,
        "The identifying column parameter must be set for the segmentation.")
      assert(baseAttrName != FEOption.unset.id,
        "The base ID attribute parameter must be set.")
      val baseColumn = table.column(baseColumnName)
      val segColumn = table.column(segColumnName)
      val baseAttr = project.vertexAttributes(baseAttrName)
      val segmentation = project.segmentation(params("name"))

      val segAttr = typedImport(segmentation, baseColumn, segColumn, baseAttr)
      segmentation.newVertexAttribute(segColumnName, segAttr)
    }

    def typedImport[A, B](
      segmentation: SegmentationEditor,
      baseColumn: Attribute[A], segColumn: Attribute[B], baseAttr: Attribute[_]): Attribute[B] = {
      // Merge by segment ID to create the segments.
      val merge = {
        val op = graph_operations.MergeVertices[B]()
        op(op.attr, segColumn).result
      }
      segmentation.setVertexSet(merge.segments, idAttr = "id")
      // Move segment ID to the segments.
      val segAttr = aggregateViaConnection(
        merge.belongsTo,
        AttributeWithLocalAggregator(segColumn, graph_operations.Aggregator.MostCommon[B]()))
      implicit val ta = baseColumn.typeTag
      implicit val tb = segColumn.typeTag
      // Import belongs-to relationship as edges between the base and the segmentation.
      val imp = graph_operations.ImportEdgesForExistingVertices.run(
        baseAttr.runtimeSafeCast[A], segAttr, baseColumn, segColumn)
      segmentation.belongsTo = imp.edges
      segAttr
    }
  })

  register("Define segmentation links from matching attributes",
    new StructureOperation(_, _) with SegOp {
      def segmentationParameters = List(
        Choice(
          "base-id-attr",
          s"Identifying vertex attribute in base project",
          options = FEOption.list(parent.vertexAttributeNames[String].toList)),
        Choice(
          "seg-id-attr",
          s"Identifying vertex attribute in segmentation",
          options = vertexAttributes[String]))
      def enabled =
        isSegmentation &&
          FEStatus.assert(
            vertexAttributes[String].nonEmpty, "No string vertex attributes in this segmentation") &&
            FEStatus.assert(
              parent.vertexAttributeNames[String].nonEmpty, "No string vertex attributes in base project")
      def apply(params: Map[String, String]) = {
        val baseIdAttr = parent.vertexAttributes(params("base-id-attr")).runtimeSafeCast[String]
        val segIdAttr = project.vertexAttributes(params("seg-id-attr")).runtimeSafeCast[String]
        val op = graph_operations.EdgesFromBipartiteAttributeMatches[String]()
        seg.belongsTo = op(op.fromAttr, baseIdAttr)(op.toAttr, segIdAttr).result.edges
      }
    })

  register("Union with another project", new StructureOperation(_, _) {
    def parameters = List(
      Choice(
        "other",
        "Other project's name",
        options = readableProjectCheckpoints,
        allowUnknownOption = true),
      Param("id-attr", "ID attribute name", defaultValue = "new_id"))
    def enabled = hasVertexSet
    override def summary(params: Map[String, String]) = {
      val (cp, title, suffix) = FEOption.unpackTitledCheckpoint(params("other"))
      s"Union with $title"
    }

    def checkTypeCollision(other: ProjectViewer) = {
      val commonAttributeNames =
        project.vertexAttributes.keySet & other.vertexAttributes.keySet

      for (name <- commonAttributeNames) {
        val a1 = project.vertexAttributes(name)
        val a2 = other.vertexAttributes(name)
        assert(a1.typeTag.tpe =:= a2.typeTag.tpe,
          s"Attribute '$name' has conflicting types in the two projects: " +
            s"(${a1.typeTag.tpe} and ${a2.typeTag.tpe})")
      }

    }
    def apply(params: Map[String, String]): Unit = {
      val otherId = params("other")
      val (cp, _, suffix) = FEOption.unpackTitledCheckpoint(
        otherId,
        customError =
          s"Obsolete project reference: $otherId. Please select a new project from the dropdown.")
      assert(suffix == "", s"Invalid project reference $otherId with suffix $suffix")
      val other = new RootProjectViewer(manager.checkpointRepo.readCheckpoint(cp))
      if (other.vertexSet == null) {
        // Nothing to do
        return
      }
      checkTypeCollision(other)
      val vsUnion = {
        val op = graph_operations.VertexSetUnion(2)
        op(op.vss, Seq(project.vertexSet, other.vertexSet)).result
      }

      val newVertexAttributes = unifyAttributes(
        project.vertexAttributes
          .map {
            case (name, attr) =>
              name -> attr.pullVia(vsUnion.injections(0).reverse)
          },
        other.vertexAttributes
          .map {
            case (name, attr) =>
              name -> attr.pullVia(vsUnion.injections(1).reverse)
          })
      val ebInduced = Option(project.edgeBundle).map { eb =>
        val op = graph_operations.InducedEdgeBundle()
        val mapping = vsUnion.injections(0)
        op(op.srcMapping, mapping)(op.dstMapping, mapping)(op.edges, project.edgeBundle).result
      }
      val otherEbInduced = Option(other.edgeBundle).map { eb =>
        val op = graph_operations.InducedEdgeBundle()
        val mapping = vsUnion.injections(1)
        op(op.srcMapping, mapping)(op.dstMapping, mapping)(op.edges, other.edgeBundle).result
      }

      val (newEdgeBundle, myEbInjection, otherEbInjection): (EdgeBundle, EdgeBundle, EdgeBundle) =
        if (ebInduced.isDefined && !otherEbInduced.isDefined) {
          (ebInduced.get.induced, ebInduced.get.embedding, null)
        } else if (!ebInduced.isDefined && otherEbInduced.isDefined) {
          (otherEbInduced.get.induced, null, otherEbInduced.get.embedding)
        } else if (ebInduced.isDefined && otherEbInduced.isDefined) {
          val idUnion = {
            val op = graph_operations.VertexSetUnion(2)
            op(
              op.vss,
              Seq(ebInduced.get.induced.idSet, otherEbInduced.get.induced.idSet))
              .result
          }
          val ebUnion = {
            val op = graph_operations.EdgeBundleUnion(2)
            op(
              op.ebs, Seq(ebInduced.get.induced.entity, otherEbInduced.get.induced.entity))(
                op.injections, idUnion.injections.map(_.entity)).result.union
          }
          (ebUnion,
            idUnion.injections(0).reverse.concat(ebInduced.get.embedding),
            idUnion.injections(1).reverse.concat(otherEbInduced.get.embedding))
        } else {
          (null, null, null)
        }
      val newEdgeAttributes = unifyAttributes(
        project.edgeAttributes
          .map {
            case (name, attr) => name -> attr.pullVia(myEbInjection)
          },
        other.edgeAttributes
          .map {
            case (name, attr) => name -> attr.pullVia(otherEbInjection)
          })

      project.vertexSet = vsUnion.union
      for ((name, attr) <- newVertexAttributes) {
        project.newVertexAttribute(name, attr) // Clear notes.
      }
      val idAttr = params("id-attr")
      assert(
        !project.vertexAttributes.contains(idAttr),
        s"The project already contains a field called '$idAttr'. Please pick a different name.")
      project.newVertexAttribute(idAttr, project.vertexSet.idAttribute)
      project.edgeBundle = newEdgeBundle
      project.edgeAttributes = newEdgeAttributes
    }
  })

  register("Fingerprinting based on attributes", new SpecialtyOperation(_, _) {
    def parameters = List(
      Choice("leftName", "First ID attribute", options = vertexAttributes[String]),
      Choice("rightName", "Second ID attribute", options = vertexAttributes[String]),
      Choice("weights", "Edge weights",
        options = FEOption.noWeight +: edgeAttributes[Double], mandatory = false),
      NonNegInt("mo", "Minimum overlap", default = 1),
      Ratio("ms", "Minimum similarity", defaultValue = "0.5"),
      Param(
        "extra",
        "Fingerprinting algorithm additional parameters",
        mandatory = false,
        defaultValue = ""))
    def enabled =
      hasEdgeBundle &&
        FEStatus.assert(vertexAttributes[String].size >= 2, "Two string attributes are needed.")
    def apply(params: Map[String, String]): Unit = {
      val mo = params("mo").toInt
      val ms = params("ms").toDouble
      assert(mo >= 1, "Minimum overlap cannot be less than 1.")
      val leftName = project.vertexAttributes(params("leftName")).runtimeSafeCast[String]
      val rightName = project.vertexAttributes(params("rightName")).runtimeSafeCast[String]
      val weightsName = params.getOrElse("weights", FEOption.noWeight.id)
      val weights =
        if (weightsName == FEOption.noWeight.id) project.edgeBundle.const(1.0)
        else project.edgeAttributes(params("weights")).runtimeSafeCast[Double]

      val candidates = {
        val op = graph_operations.FingerprintingCandidates()
        op(op.es, project.edgeBundle)(op.leftName, leftName)(op.rightName, rightName)
          .result.candidates
      }
      val fingerprinting = {
        // TODO: This is a temporary hack to facilitate experimentation with the underlying backend
        // operation w/o too much disruption to users. Should be removed once we are clear on what
        // we want to provide for fingerprinting.
        val baseParams = s""""minimumOverlap": $mo, "minimumSimilarity": $ms"""
        val extraParams = params.getOrElse("extra", "")
        val paramsJson = if (extraParams == "") baseParams else (baseParams + ", " + extraParams)
        val op = graph_operations.Fingerprinting.fromJson(json.Json.parse(s"{$paramsJson}"))
        op(
          op.leftEdges, project.edgeBundle)(
            op.leftEdgeWeights, weights)(
              op.rightEdges, project.edgeBundle)(
                op.rightEdgeWeights, weights)(
                  op.candidates, candidates)
          .result
      }
      val newLeftName = leftName.pullVia(fingerprinting.matching.reverse)
      val newRightName = rightName.pullVia(fingerprinting.matching)

      project.scalars("fingerprinting matches found") = fingerprinting.matching.countScalar
      project.vertexAttributes(params("leftName")) = newLeftName.fallback(leftName)
      project.vertexAttributes(params("rightName")) = newRightName.fallback(rightName)
      project.newVertexAttribute(
        params("leftName") + " similarity score", fingerprinting.leftSimilarities)
      project.newVertexAttribute(
        params("rightName") + " similarity score", fingerprinting.rightSimilarities)
    }
  })

  register("Copy vertex attributes from segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Attribute name prefix", defaultValue = seg.segmentationName))
    def enabled =
      isSegmentation &&
        FEStatus.assert(vertexAttributes.size > 0, "No vertex attributes") &&
        FEStatus.assert(parent.vertexSet != null, s"No vertices on $parent") &&
        FEStatus.assert(seg.belongsTo.properties.isFunction,
          s"Vertices in base project are not guaranteed to be contained in only one segment")
    def apply(params: Map[String, String]): Unit = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- project.vertexAttributes.toMap) {
        parent.newVertexAttribute(
          prefix + name,
          attr.pullVia(seg.belongsTo))
      }
    }
  })

  register("Copy vertex attributes to segmentation", new PropagationOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Attribute name prefix"))
    def enabled =
      isSegmentation &&
        hasVertexSet &&
        FEStatus.assert(parent.vertexAttributes.size > 0, "No vertex attributes on $parent") &&
        FEStatus.assert(seg.belongsTo.properties.isReversedFunction,
          "Segments are not guaranteed to contain only one vertex")
    def apply(params: Map[String, String]): Unit = {
      val prefix = if (params("prefix").nonEmpty) params("prefix") + "_" else ""
      for ((name, attr) <- parent.vertexAttributes.toMap) {
        project.newVertexAttribute(
          prefix + name,
          attr.pullVia(seg.belongsTo.reverse))
      }
    }
  })

  register("Compare segmentation edges", new GlobalOperation(_, _) {
    def isCompatibleSegmentation(segmentation: SegmentationEditor): Boolean = {
      return segmentation.edgeBundle != null &&
        segmentation.belongsTo.properties.compliesWith(EdgeBundleProperties.identity)
    }

    val possibleSegmentations = FEOption.list(
      project.segmentations
        .filter(isCompatibleSegmentation)
        .map { seg => seg.segmentationName }
        .toList)

    override def parameters = List(
      Choice("golden", "Golden segmentation", options = possibleSegmentations),
      Choice("test", "Test segmentation", options = possibleSegmentations)
    )
    def enabled = FEStatus.assert(
      possibleSegmentations.size >= 2,
      "At least two segmentations are needed. Both should have edges " +
        "and both have to contain the same vertices as the base project. " +
        "(For example, use the copy graph into segmentation operation.)")

    def apply(params: Map[String, String]): Unit = {
      val golden = project.existingSegmentation(params("golden"))
      val test = project.existingSegmentation(params("test"))
      val op = graph_operations.CompareSegmentationEdges()
      val result = op(
        op.goldenBelongsTo, golden.belongsTo)(
          op.testBelongsTo, test.belongsTo)(
            op.goldenEdges, golden.edgeBundle)(
              op.testEdges, test.edgeBundle).result
      test.scalars("precision") = result.precision
      test.scalars("recall") = result.recall
      test.edgeAttributes("present_in_" + golden.segmentationName) = result.presentInGolden
      golden.edgeAttributes("present_in_" + test.segmentationName) = result.presentInTest
    }

  })

  register("Fingerprinting between project and segmentation", new SpecialtyOperation(_, _) with SegOp {
    def segmentationParameters = List(
      NonNegInt("mo", "Minimum overlap", default = 1),
      Ratio("ms", "Minimum similarity", defaultValue = "0.0"),
      Param(
        "extra",
        "Fingerprinting algorithm additional parameters",
        mandatory = false,
        defaultValue = ""))
    def enabled =
      isSegmentation &&
        hasEdgeBundle && FEStatus.assert(parent.edgeBundle != null, s"No edges on $parent")
    def apply(params: Map[String, String]): Unit = {
      val mo = params("mo").toInt
      val ms = params("ms").toDouble

      // We are setting the stage here for the generic fingerprinting operation. For a vertex A
      // on the left (base project) side and a vertex B on the right (segmentation) side we
      // want to "create" a common neighbor for fingerprinting purposes iff a neighbor of A (A') is
      // connected to a neigbor of B (B'). In practice, to make the setup symmetric, we will
      // actually create two common neighbors, namely we will connect both A and B to A' and B'.
      //
      // There is one more twist, that we want to consider A being connected to B directly also
      // as an evidence for A and B being a good match. To achieve this, we basically artificially
      // make every vertex a member of its own neighborhood by adding loop edges.
      val leftWithLoops = parallelEdgeBundleUnion(parent.edgeBundle, parent.vertexSet.loops)
      val rightWithLoops = parallelEdgeBundleUnion(project.edgeBundle, project.vertexSet.loops)
      val fromLeftToRight = leftWithLoops.concat(seg.belongsTo)
      val fromRightToLeft = rightWithLoops.concat(seg.belongsTo.reverse)
      val leftEdges = generalEdgeBundleUnion(leftWithLoops, fromLeftToRight)
      val rightEdges = generalEdgeBundleUnion(rightWithLoops, fromRightToLeft)

      val candidates = {
        val op = graph_operations.FingerprintingCandidatesFromCommonNeighbors()
        op(op.leftEdges, leftEdges)(op.rightEdges, rightEdges).result.candidates
      }

      val fingerprinting = {
        // TODO: This is a temporary hack to facilitate experimentation with the underlying backend
        // operation w/o too much disruption to users. Should be removed once we are clear on what
        // we want to provide for fingerprinting.
        val baseParams = s""""minimumOverlap": $mo, "minimumSimilarity": $ms"""
        val extraParams = params.getOrElse("extra", "")
        val paramsJson = if (extraParams == "") baseParams else (baseParams + ", " + extraParams)
        val op = graph_operations.Fingerprinting.fromJson(json.Json.parse(s"{$paramsJson}"))
        op(
          op.leftEdges, leftEdges)(
            op.leftEdgeWeights, leftEdges.const(1.0))(
              op.rightEdges, rightEdges)(
                op.rightEdgeWeights, rightEdges.const(1.0))(
                  op.candidates, candidates)
          .result
      }

      project.scalars("fingerprinting matches found") = fingerprinting.matching.countScalar
      seg.belongsTo = fingerprinting.matching
      parent.newVertexAttribute(
        "fingerprinting_similarity_score", fingerprinting.leftSimilarities)
      project.newVertexAttribute(
        "fingerprinting_similarity_score", fingerprinting.rightSimilarities)
    }
  })

  register("Change project notes", new UtilityOperation(_, _) {
    def parameters = List(
      Param("notes", "New contents"))
    def enabled = FEStatus.enabled
    def apply(params: Map[String, String]) = {
      project.notes = params("notes")
    }
  })

  register("Viral modeling", new SpecialtyOperation(_, _) with SegOp {
    def segmentationParameters = List(
      Param("prefix", "Generated name prefix", defaultValue = "viral"),
      Choice("target", "Target attribute",
        options = FEOption.list(parentDoubleAttributes)),
      Ratio("test_set_ratio", "Test set ratio", defaultValue = "0.1"),
      RandomSeed("seed", "Random seed for test set selection"),
      NonNegDouble("max_deviation", "Maximal segment deviation", defaultValue = "1.0"),
      NonNegInt("min_num_defined", "Minimum number of defined attributes in a segment", default = 3),
      Ratio("min_ratio_defined", "Minimal ratio of defined attributes in a segment", defaultValue = "0.25"),
      NonNegInt("iterations", "Iterations", default = 3))
    def parentDoubleAttributes = parent.vertexAttributeNames[Double].toList
    def enabled =
      isSegmentation &&
        hasVertexSet &&
        FEStatus.assert(FEOption.list(parentDoubleAttributes).nonEmpty,
          "No numeric vertex attributes.")
    def apply(params: Map[String, String]) = {
      // partition target attribute to test and train sets
      val targetName = params("target")
      val target = parent.vertexAttributes(targetName).runtimeSafeCast[Double]
      val roles = {
        val op = graph_operations.CreateRole(params("test_set_ratio").toDouble, params("seed").toInt)
        op(op.vertices, target.vertexSet).result.role
      }
      val parted = {
        val op = graph_operations.PartitionAttribute[Double]()
        op(op.attr, target)(op.role, roles).result
      }
      val prefix = params("prefix")
      parent.newVertexAttribute(s"${prefix}_roles", roles)
      parent.newVertexAttribute(s"${prefix}_${targetName}_test", parted.test)
      var train = parted.train.entity
      val segSizes = computeSegmentSizes(seg)
      project.newVertexAttribute("size", segSizes)
      val maxDeviation = params("max_deviation")

      val coverage = {
        val op = graph_operations.CountAttributes[Double]()
        op(op.attribute, train).result.count
      }
      parent.newVertexAttribute(s"${prefix}_${targetName}_train", train)
      parent.scalars(s"$prefix $targetName coverage initial") = coverage

      var timeOfDefinition = {
        val op = graph_operations.DeriveJSDouble(JavaScript("0"), Seq("attr"))
        op(op.attrs, graph_operations.VertexAttributeToJSValue.seq(train)).result.attr.entity
      }

      // iterative prediction
      for (i <- 1 to params("iterations").toInt) {
        val segTargetAvg = {
          aggregateViaConnection(
            seg.belongsTo,
            AttributeWithLocalAggregator(train, "average"))
            .runtimeSafeCast[Double]
        }
        val segStdDev = {
          aggregateViaConnection(
            seg.belongsTo,
            AttributeWithLocalAggregator(train, "std_deviation"))
            .runtimeSafeCast[Double]
        }
        val segTargetCount = {
          aggregateViaConnection(
            seg.belongsTo,
            AttributeWithLocalAggregator(train, "count"))
            .runtimeSafeCast[Double]
        }
        val segStdDevDefined = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript(s"""
                deviation <= $maxDeviation &&
                defined / ids >= ${params("min_ratio_defined")} &&
                defined >= ${params("min_num_defined")}
                ? deviation
                : undefined"""),
            Seq("deviation", "ids", "defined"))
          op(
            op.attrs,
            graph_operations.VertexAttributeToJSValue.seq(segStdDev, segSizes, segTargetCount))
            .result.attr
        }
        project.newVertexAttribute(
          s"${prefix}_${targetName}_standard_deviation_after_iteration_$i",
          segStdDev)
        project.newVertexAttribute(
          s"${prefix}_${targetName}_average_after_iteration_$i",
          segTargetAvg)
        val predicted = {
          aggregateViaConnection(
            seg.belongsTo.reverse,
            AttributeWithWeightedAggregator(segStdDevDefined, segTargetAvg, "by_min_weight"))
            .runtimeSafeCast[Double]
        }
        train = unifyAttributeT(train, predicted)
        val partedTrain = {
          val op = graph_operations.PartitionAttribute[Double]()
          op(op.attr, train)(op.role, roles).result
        }
        val error = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript("Math.abs(test - train)"), Seq("test", "train"))
          val mae = op(
            op.attrs,
            graph_operations.VertexAttributeToJSValue.seq(
              parted.test.entity, partedTrain.test.entity)).result.attr
          aggregate(AttributeWithAggregator(mae, "average"))
        }
        val coverage = {
          val op = graph_operations.CountAttributes[Double]()
          op(op.attribute, partedTrain.train).result.count
        }
        // the attribute we use for iteration can be defined on the test set as well
        parent.newVertexAttribute(s"${prefix}_${targetName}_after_iteration_$i", train)
        parent.scalars(s"$prefix $targetName coverage after iteration $i") = coverage
        parent.scalars(s"$prefix $targetName mean absolute prediction error after iteration $i") =
          error

        timeOfDefinition = {
          val op = graph_operations.DeriveJSDouble(
            JavaScript(i.toString), Seq("attr"))
          val newDefinitions = op(
            op.attrs, graph_operations.VertexAttributeToJSValue.seq(train)).result.attr
          unifyAttributeT(timeOfDefinition, newDefinitions)
        }
      }
      parent.newVertexAttribute(s"${prefix}_${targetName}_spread_over_iterations", timeOfDefinition)
      // TODO: in the end we should calculate with the fact that the real error where the
      // original attribute is defined is 0.0
    }
  })

  register("Correlate two attributes", new GlobalOperation(_, _) {
    def parameters = List(
      Choice("attrA", "First attribute", options = vertexAttributes[Double]),
      Choice("attrB", "Second attribute", options = vertexAttributes[Double]))
    def enabled =
      FEStatus.assert(vertexAttributes[Double].nonEmpty, "No numeric vertex attributes")
    def apply(params: Map[String, String]) = {
      val attrA = project.vertexAttributes(params("attrA")).runtimeSafeCast[Double]
      val attrB = project.vertexAttributes(params("attrB")).runtimeSafeCast[Double]
      val op = graph_operations.CorrelateAttributes()
      val res = op(op.attrA, attrA)(op.attrB, attrB).result
      project.scalars(s"correlation of ${params("attrA")} and ${params("attrB")}") =
        res.correlation
    }
  })

  register("Filter by attributes", new StructureOperation(_, _) {
    def parameters =
      vertexAttributes.toList.map {
        attr => Param(s"filterva-${attr.id}", attr.id, mandatory = false)
      } ++
        project.segmentations.toList.map {
          seg =>
            Param(
              s"filterva-${seg.viewer.equivalentUIAttributeTitle}",
              seg.segmentationName,
              mandatory = false)
        } ++
        edgeAttributes.toList.map {
          attr => Param(s"filterea-${attr.id}", attr.id, mandatory = false)
        }
    def enabled =
      FEStatus.assert(vertexAttributes.nonEmpty, "No vertex attributes") ||
        FEStatus.assert(edgeAttributes.nonEmpty, "No edge attributes")
    val vaFilter = "filterva-(.*)".r
    val eaFilter = "filterea-(.*)".r

    override def summary(params: Map[String, String]) = {
      val filterStrings = params.collect {
        case (vaFilter(name), filter) if filter.nonEmpty => s"$name $filter"
        case (eaFilter(name), filter) if filter.nonEmpty => s"$name $filter"
      }
      "Filter " + filterStrings.mkString(", ")
    }
    def apply(params: Map[String, String]) = {
      val vertexFilters = params.collect {
        case (vaFilter(name), filter) if filter.nonEmpty =>
          // The filter may be for a segmentation's equivalent attribute or for a vertex attribute.
          val segs = project.segmentations.map(_.viewer)
          val segGUIDOpt =
            segs.find(_.equivalentUIAttributeTitle == name).map(_.belongsToAttribute.gUID)
          val gUID = segGUIDOpt.getOrElse(project.vertexAttributes(name).gUID)
          FEVertexAttributeFilter(gUID.toString, filter)
      }.toSeq

      if (vertexFilters.nonEmpty) {
        val vertexEmbedding = FEFilters.embedFilteredVertices(
          project.vertexSet, vertexFilters, heavy = true)
        project.pullBack(vertexEmbedding)
      }
      val edgeFilters = params.collect {
        case (eaFilter(name), filter) if filter.nonEmpty =>
          val attr = project.edgeAttributes(name)
          FEVertexAttributeFilter(attr.gUID.toString, filter)
      }.toSeq
      assert(vertexFilters.nonEmpty || edgeFilters.nonEmpty, "No filters specified.")
      if (edgeFilters.nonEmpty) {
        val edgeEmbedding = FEFilters.embedFilteredVertices(
          project.edgeBundle.idSet, edgeFilters, heavy = true)
        project.pullBackEdges(edgeEmbedding)
      }
    }
  })

  register("Save UI status as graph attribute", new UtilityOperation(_, _) {
    def parameters = List(
      // In the future we may want a special kind for this so that users don't see JSON.
      Param("scalarName", "Name of new graph attribute"),
      Param("uiStatusJson", "UI status as JSON"))

    def enabled = FEStatus.enabled

    def apply(params: Map[String, String]) = {
      import UIStatusSerialization._
      val j = json.Json.parse(params("uiStatusJson"))
      val uiStatus = j.as[UIStatus]
      project.scalars(params("scalarName")) =
        graph_operations.CreateUIStatusScalar(uiStatus).result.created
    }
  })

  register("Metagraph", new StructureOperation(_, _) {
    def parameters = List(
      Param("timestamp", "Current timestamp", defaultValue = graph_util.Timestamp.toString))
    def enabled =
      FEStatus.assert(user.isAdmin, "Requires administrator privileges") && hasNoVertexSet
    def apply(params: Map[String, String]) = {
      val t = params("timestamp")
      val mg = graph_operations.MetaGraph(t, Some(env)).result
      project.vertexSet = mg.vs
      project.newVertexAttribute("GUID", mg.vGUID)
      project.newVertexAttribute("kind", mg.vKind)
      project.newVertexAttribute("name", mg.vName)
      project.newVertexAttribute("progress", mg.vProgress)
      project.newVertexAttribute("id", project.vertexSet.idAttribute)
      project.edgeBundle = mg.es
      project.newEdgeAttribute("kind", mg.eKind)
      project.newEdgeAttribute("name", mg.eName)
    }
  })

  register("Copy edges to segmentation", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List()
    def enabled = isSegmentation && hasNoEdgeBundle &&
      FEStatus.assert(parent.edgeBundle != null, "No edges on base project")
    def apply(params: Map[String, String]) = {
      val induction = {
        val op = graph_operations.InducedEdgeBundle()
        op(op.srcMapping, seg.belongsTo)(op.dstMapping, seg.belongsTo)(op.edges, parent.edgeBundle).result
      }
      project.edgeBundle = induction.induced
      for ((name, attr) <- parent.edgeAttributes) {
        project.edgeAttributes(name) = attr.pullVia(induction.embedding)
      }
    }
  })

  register("Copy edges to base project", new StructureOperation(_, _) with SegOp {
    def segmentationParameters = List()
    def enabled = isSegmentation &&
      hasEdgeBundle &&
      FEStatus.assert(parent.edgeBundle == null, "There are already edges on base project")
    def apply(params: Map[String, String]) = {
      val seg = project.asSegmentation
      val reverseBelongsTo = seg.belongsTo.reverse
      val induction = {
        val op = graph_operations.InducedEdgeBundle()
        op(op.srcMapping, reverseBelongsTo)(
          op.dstMapping, reverseBelongsTo)(
            op.edges, seg.edgeBundle).result
      }
      parent.edgeBundle = induction.induced
      for ((name, attr) <- seg.edgeAttributes) {
        parent.edgeAttributes(name) = attr.pullVia(induction.embedding)
      }
    }
  })

  register("Create segmentation from SQL", new StructureOperation(_, _) with SegOp {
    override def parameters = List(
      Param("name", "Name"),
      Code("sql", "SQL", defaultValue = "select * from vertices"))
    def segmentationParameters = List()
    def enabled = FEStatus.assert(true, "")

    def apply(params: Map[String, String]) = {
      val sql = params("sql")
      val table = env.sqlHelper.sqlToTable(project.viewer, params("sql"))
      val tableSegmentation = project.segmentation(params("name"))
      tableSegmentation.vertexSet = table.idSet
      for ((name, column) <- table.columns) {
        tableSegmentation.newVertexAttribute(name, column)
      }
    }
  })

  def computeSegmentSizes(segmentation: SegmentationEditor): Attribute[Double] = {
    val op = graph_operations.OutDegree()
    op(op.es, segmentation.belongsTo.reverse).result.outDegree
  }

  def toDouble(attr: Attribute[_]): Attribute[Double] = {
    if (attr.is[String])
      attr.runtimeSafeCast[String].asDouble
    else if (attr.is[Long])
      attr.runtimeSafeCast[Long].asDouble
    else
      throw new AssertionError(s"Unexpected type (${attr.typeTag}) on $attr")
  }

  def parseAggregateParams(params: Map[String, String]) = {
    val aggregate = "aggregate-(.*)".r
    params.toSeq.collect {
      case (aggregate(attr), choices) if choices.nonEmpty => attr -> choices
    }.flatMap {
      case (attr, choices) => choices.split(",", -1).map(attr -> _)
    }
  }
  def aggregateParams(
    attrs: Iterable[(String, Attribute[_])],
    needsGlobal: Boolean = false,
    weighted: Boolean = false): List[OperationParameterMeta] = {
    attrs.toList.map {
      case (name, attr) =>
        val options = if (attr.is[Double]) {
          if (weighted) { // At the moment all weighted aggregators are global.
            FEOption.list("weighted_average", "by_max_weight", "by_min_weight", "weighted_sum")
          } else if (needsGlobal) {
            FEOption.list("average", "count", "first", "max", "min", "std_deviation", "sum")

          } else {
            FEOption.list(
              "average", "count", "count_distinct", "max", "median", "min", "most_common",
              "set", "std_deviation", "sum", "vector")
          }
        } else if (attr.is[String]) {
          if (weighted) { // At the moment all weighted aggregators are global.
            FEOption.list("by_max_weight", "by_min_weight")
          } else if (needsGlobal) {
            FEOption.list("count", "first")
          } else {
            FEOption.list(
              "most_common", "count_distinct", "majority_50", "majority_100",
              "count", "vector", "set")
          }
        } else {
          if (weighted) { // At the moment all weighted aggregators are global.
            FEOption.list("by_max_weight", "by_min_weight")
          } else if (needsGlobal) {
            FEOption.list("count", "first")
          } else {
            FEOption.list("count", "count_distinct", "median", "most_common", "set", "vector")
          }
        }
        TagList(s"aggregate-$name", name, options = options)
    }
  }

  // Performs AggregateAttributeToScalar.
  private def aggregate[From, Intermediate, To](
    attributeWithAggregator: AttributeWithAggregator[From, Intermediate, To]): Scalar[To] = {
    val op = graph_operations.AggregateAttributeToScalar(attributeWithAggregator.aggregator)
    op(op.attr, attributeWithAggregator.attr).result.aggregated
  }

  // Performs AggregateByEdgeBundle.
  private def aggregateViaConnection[From, To](
    connection: EdgeBundle,
    attributeWithAggregator: AttributeWithLocalAggregator[From, To]): Attribute[To] = {
    val op = graph_operations.AggregateByEdgeBundle(attributeWithAggregator.aggregator)
    op(op.connection, connection)(op.attr, attributeWithAggregator.attr).result.attr
  }

  // Performs AggregateFromEdges.
  private def aggregateFromEdges[From, To](
    edges: EdgeBundle,
    attributeWithAggregator: AttributeWithLocalAggregator[From, To]): Attribute[To] = {
    val op = graph_operations.AggregateFromEdges(attributeWithAggregator.aggregator)
    val res = op(op.edges, edges)(op.eattr, attributeWithAggregator.attr).result
    res.dstAttr
  }

  def stripDuplicateEdges(eb: EdgeBundle): EdgeBundle = {
    val op = graph_operations.StripDuplicateEdgesFromBundle()
    op(op.es, eb).result.unique
  }

  object Direction {
    // Options suitable when edge attributes are involved.
    val attrOptions = FEOption.list("incoming edges", "outgoing edges", "all edges")
    // Options suitable when edge attributes are not involved.
    val options = attrOptions ++
      FEOption.list(
        "symmetric edges", "in-neighbors", "out-neighbors", "all neighbors", "symmetric neighbors")
    // Neighborhood directions correspond to these
    // edge directions, but they also retain only one A->B edge in
    // the output edgeBundle
    private val neighborOptionMapping = Map(
      "in-neighbors" -> "incoming edges",
      "out-neighbors" -> "outgoing edges",
      "all neighbors" -> "all edges",
      "symmetric neighbors" -> "symmetric edges"
    )
  }
  case class Direction(direction: String, origEB: EdgeBundle, reversed: Boolean = false) {
    val unchangedOut: (EdgeBundle, Option[EdgeBundle]) = (origEB, None)
    val reversedOut: (EdgeBundle, Option[EdgeBundle]) = {
      val op = graph_operations.ReverseEdges()
      val res = op(op.esAB, origEB).result
      (res.esBA, Some(res.injection))
    }
    private def computeEdgeBundleAndPullBundleOpt(dir: String): (EdgeBundle, Option[EdgeBundle]) = {
      dir match {
        case "incoming edges" => if (reversed) reversedOut else unchangedOut
        case "outgoing edges" => if (reversed) unchangedOut else reversedOut
        case "all edges" =>
          val op = graph_operations.AddReversedEdges()
          val res = op(op.es, origEB).result
          (res.esPlus, Some(res.newToOriginal))
        case "symmetric edges" =>
          // Use "null" as the injection because it is an error to use
          // "symmetric edges" with edge attributes.
          (origEB.makeSymmetric, Some(null))
      }
    }

    val (edgeBundle, pullBundleOpt): (EdgeBundle, Option[EdgeBundle]) = {
      if (Direction.neighborOptionMapping.contains(direction)) {
        val (eB, pBO) = computeEdgeBundleAndPullBundleOpt(Direction.neighborOptionMapping(direction))
        (stripDuplicateEdges(eB), pBO)
      } else {
        computeEdgeBundleAndPullBundleOpt(direction)
      }
    }

    def pull[T](attribute: Attribute[T]): Attribute[T] = {
      pullBundleOpt.map(attribute.pullVia(_)).getOrElse(attribute)
    }
  }

  private def unifyAttributeT[T](a1: Attribute[T], a2: Attribute[_]): Attribute[T] = {
    a1.fallback(a2.runtimeSafeCast(a1.typeTag))
  }
  def unifyAttribute(a1: Attribute[_], a2: Attribute[_]): Attribute[_] = {
    unifyAttributeT(a1, a2)
  }

  def unifyAttributes(
    as1: Iterable[(String, Attribute[_])],
    as2: Iterable[(String, Attribute[_])]): Map[String, Attribute[_]] = {

    val m1 = as1.toMap
    val m2 = as2.toMap
    m1.keySet.union(m2.keySet)
      .map(k => k -> (m1.get(k) ++ m2.get(k)).reduce(unifyAttribute _))
      .toMap
  }

  def newScalar(data: String): Scalar[String] = {
    val op = graph_operations.CreateStringScalar(data)
    op.result.created
  }

  // Whether a JavaScript expression contains a given identifier.
  // It's a best-effort implementation with no guarantees of correctness.
  def containsIdentifierJS(expr: String, identifier: String): Boolean = {
    val re = "(?s).*\\b" + java.util.regex.Pattern.quote(identifier) + "\\b.*"
    expr.matches(re)
  }
}

object Operations {
  def addNotesOperation(notes: String): FEOperationSpec = {
    FEOperationSpec("Change-project-notes", Map("notes" -> notes))
  }
}
