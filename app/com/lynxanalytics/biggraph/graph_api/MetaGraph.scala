package com.lynxanalytics.biggraph.graph_api

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.UUID
import org.apache.spark.rdd.RDD
import scala.reflect.runtime.universe._
import scala.Symbol // There is a Symbol in the universe package too.
import scala.collection.mutable

import attributes.AttributeSignature

sealed trait MetaGraphEntity extends Serializable {
  val source: MetaGraphOperationInstance
  val name: Symbol
  // Implement from source operation's GUID, name and the actual class of this component.
  val gUID: UUID = {
    val buffer = new ByteArrayOutputStream
    val objectStream = new ObjectOutputStream(buffer)
    objectStream.writeObject(name)
    objectStream.writeObject(source.gUID)
    objectStream.writeObject(this.getClass.toString)
    objectStream.close()
    UUID.nameUUIDFromBytes(buffer.toByteArray)
  }
  override def toString = s"$source/${name.name}"
}

case class VertexSet(source: MetaGraphOperationInstance,
                     name: Symbol) extends MetaGraphEntity

case class EdgeBundle(source: MetaGraphOperationInstance,
                      name: Symbol) extends MetaGraphEntity {
  @transient lazy val (srcName, dstName) = source.operation.outputEdgeBundles(name)
  @transient lazy val srcVertexSet: VertexSet = source.entities.vertexSets(srcName)
  @transient lazy val dstVertexSet: VertexSet = source.entities.vertexSets(dstName)
  @transient lazy val isLocal = srcVertexSet == dstVertexSet
}

sealed trait Attribute[T] extends MetaGraphEntity {
  val typeTag: TypeTag[T]
  def runtimeSafeCast[S: TypeTag]: Attribute[S]
}

case class VertexAttribute[T: TypeTag](source: MetaGraphOperationInstance,
                                       name: Symbol)
    extends Attribute[T] with RuntimeSafeCastable[T, VertexAttribute] {
  val typeTag = implicitly[TypeTag[T]]
  @transient lazy val vertexSet: VertexSet =
    source.entities.vertexSets(source.operation.outputVertexAttributes(name)._1)
}

case class EdgeAttribute[T: TypeTag](source: MetaGraphOperationInstance,
                                     name: Symbol)
    extends Attribute[T] with RuntimeSafeCastable[T, EdgeAttribute] {
  val typeTag = implicitly[TypeTag[T]]
  @transient lazy val edgeBundle: EdgeBundle =
    source.entities.edgeBundles(source.operation.outputEdgeAttributes(name)._1)
}

trait MetaGraphOperation extends Serializable {
  // Override "signature" to easily describe the inputs and outputs of your operation. E.g.:
  //     class MyOperation extends MetaGraphOperation {
  //       def signature = newSignature
  //         .inputGraph("input-vertices", "input-edges")
  //         .outputVertexAttribute[Double]("input-vertices", "my-attribute")
  //     }
  def signature: MetaGraphOperationSignature
  protected def newSignature = new MetaGraphOperationSignature

  // Names of vertex set inputs for this operation.
  def inputVertexSets: Set[Symbol] = signature.inputVertexSets.toSet

  // Names of input bundles together with their source and destination vertex set names (which all
  // must be elements of inputVertexSets).
  def inputEdgeBundles: Map[Symbol, (Symbol, Symbol)] = signature.inputEdgeBundles.toMap

  // Names of input vertex attributes together with their corresponding VertexSet names (which all
  // must be elements of inputVertexSets) and the types of the attributes.
  def inputVertexAttributes: Map[Symbol, (Symbol, TypeTag[_])] = signature.inputVertexAttributes.toMap

  // Names of input edge attributes together with their corresponding EdgeBundle names (which all
  // must be keys of inputEdgeBundles) and the types of the attributes.
  def inputEdgeAttributes: Map[Symbol, (Symbol, TypeTag[_])] = signature.inputEdgeAttributes.toMap

  // Names of vertex sets newly created by this operation.
  // (outputVertexSets /\ inputVertexSets needs to be empty).
  def outputVertexSets: Set[Symbol] = signature.outputVertexSets.toSet

  // Names of bundles newly created by this operation together with their source and destination
  // vertex set names (which all must be elements of inputVertexSets \/ outputVertexSets).
  def outputEdgeBundles: Map[Symbol, (Symbol, Symbol)] = signature.outputEdgeBundles.toMap

  // Names of vertex attributes newly created by this operation together with their corresponding
  // VertexSet names (which all must be elements of inputVertexSets \/ outputVertexSets)
  // and the types of the attributes.
  def outputVertexAttributes: Map[Symbol, (Symbol, TypeTag[_])] = signature.outputVertexAttributes.toMap

  // Names of edge attributes newly created by this operation together with their corresponding
  // EdgeBundle names (which all must be keys of inputEdgeBundles \/ outputEdgeBundles)
  // and the types of the attributes.
  def outputEdgeAttributes: Map[Symbol, (Symbol, TypeTag[_])] = signature.outputEdgeAttributes.toMap

  // Checks whether the complete input signature is valid for this operation.
  def validateInput(input: MetaDataSet): Boolean = ???

  val gUID: UUID = {
    val buffer = new ByteArrayOutputStream
    val objectStream = new ObjectOutputStream(buffer)
    objectStream.writeObject(this)
    objectStream.close()
    UUID.nameUUIDFromBytes(buffer.toByteArray)
  }

  def execute(inputs: DataSet, outputs: DataSetBuilder, rc: RuntimeContext): Unit
}

class MetaGraphOperationSignature private[graph_api] {
  val inputVertexSets: mutable.Set[Symbol] = mutable.Set()
  val inputEdgeBundles: mutable.Map[Symbol, (Symbol, Symbol)] = mutable.Map()
  val inputVertexAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val inputEdgeAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val outputVertexSets: mutable.Set[Symbol] = mutable.Set()
  val outputEdgeBundles: mutable.Map[Symbol, (Symbol, Symbol)] = mutable.Map()
  val outputVertexAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val outputEdgeAttributes: mutable.Map[Symbol, (Symbol, TypeTag[_])] = mutable.Map()
  val allNames: mutable.Set[Symbol] = mutable.Set()
  def inputVertexSet(name: Symbol) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    inputVertexSets += name
    allNames += name
    this
  }
  def inputEdgeBundle(name: Symbol, srcDst: (Symbol, Symbol), create: Boolean = false) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    inputEdgeBundles(name) = srcDst
    val (src, dst) = srcDst
    allNames += name
    if (create) {
      inputVertexSet(src)
      inputVertexSet(dst)
    }
    this
  }
  def inputGraph(vertexSetName: Symbol, edgeBundleName: Symbol) = {
    inputVertexSet(vertexSetName)
    inputEdgeBundle(edgeBundleName, vertexSetName -> vertexSetName)
  }
  def inputVertexAttribute[T: TypeTag](attributeName: Symbol,
                                       vertexSetName: Symbol,
                                       create: Boolean = false) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    inputVertexAttributes(attributeName) = vertexSetName -> typeTag[T]
    allNames += attributeName
    if (create) {
      inputVertexSet(vertexSetName)
    }
    this
  }
  def inputEdgeAttribute[T: TypeTag](attributeName: Symbol, edgeBundleName: Symbol) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    inputEdgeAttributes(attributeName) = edgeBundleName -> typeTag[T]
    allNames += attributeName
    this
  }
  def outputVertexSet(name: Symbol) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    outputVertexSets += name
    allNames += name
    this
  }
  def outputEdgeBundle(name: Symbol, srcDst: (Symbol, Symbol)) = {
    assert(!allNames.contains(name), s"Double-defined: $name")
    outputEdgeBundles(name) = srcDst
    allNames += name
    this
  }
  def outputGraph(vertexSetName: Symbol, edgeBundleName: Symbol) = {
    outputVertexSet(vertexSetName)
    outputEdgeBundle(edgeBundleName, vertexSetName -> vertexSetName)
  }
  def outputVertexAttribute[T: TypeTag](attributeName: Symbol, vertexSetName: Symbol) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    outputVertexAttributes(attributeName) = vertexSetName -> typeTag[T]
    allNames += attributeName
    this
  }
  def outputEdgeAttribute[T: TypeTag](attributeName: Symbol, edgeBundleName: Symbol) = {
    assert(!allNames.contains(attributeName), s"Double-defined: $attributeName")
    outputEdgeAttributes(attributeName) = edgeBundleName -> typeTag[T]
    allNames += attributeName
    this
  }
}

/*
 * Base class for concrete instances of MetaGraphOperations. An instance of an operation is
 * the operation together with concrete input vertex sets and edge bundles.
 */
case class MetaGraphOperationInstance(
    val operation: MetaGraphOperation,
    val inputs: MetaDataSet) {

  val gUID: UUID = {
    val buffer = new ByteArrayOutputStream
    val objectStream = new ObjectOutputStream(buffer)
    objectStream.writeObject(operation.gUID)
    inputs.all.keys.toSeq.map(_ match { case Symbol(s) => s }).sorted.foreach { name =>
      objectStream.writeObject(name)
      objectStream.writeObject(inputs.all(Symbol(name)).gUID)
    }
    objectStream.close()
    UUID.nameUUIDFromBytes(buffer.toByteArray)
  }

  val outputs = MetaDataSet(
    operation.outputVertexSets.map(n => n -> VertexSet(this, n)).toMap,
    operation.outputEdgeBundles.keys.map(n => n -> EdgeBundle(this, n)).toMap,
    operation.outputVertexAttributes.map {
      case (n, (vs, tt)) => n -> VertexAttribute(this, n)(tt)
    }.toMap,
    operation.outputEdgeAttributes.map {
      case (n, (vs, tt)) => n -> EdgeAttribute(this, n)(tt)
    }.toMap)

  val entities = inputs ++ outputs

  override def toString = s"[$operation]($inputs)"
}

sealed trait EntityData {
  val rdd: RDD[_]
}

class VertexSetData(val vertexSet: VertexSet,
                    val rdd: VertexSetRDD) extends EntityData

class EdgeBundleData(val edgeBundle: EdgeBundle,
                     val rdd: EdgeBundleRDD) extends EntityData

sealed trait AttributeData[T] extends EntityData {
  val typeTag: TypeTag[T]
  def runtimeSafeCast[S: TypeTag]: AttributeData[S]
  val rdd: AttributeRDD[T]
}

class VertexAttributeData[T](val vertexAttribute: VertexAttribute[T],
                             val rdd: AttributeRDD[T])
    extends AttributeData[T] with RuntimeSafeCastable[T, VertexAttributeData] {
  val typeTag = vertexAttribute.typeTag
}

class EdgeAttributeData[T](val edgeAttribute: EdgeAttribute[T],
                           val rdd: AttributeRDD[T])
    extends AttributeData[T] with RuntimeSafeCastable[T, EdgeAttributeData] {
  val typeTag = edgeAttribute.typeTag
}

// A bundle of metadata types.
case class MetaDataSet(vertexSets: Map[Symbol, VertexSet] = Map(),
                       edgeBundles: Map[Symbol, EdgeBundle] = Map(),
                       vertexAttributes: Map[Symbol, VertexAttribute[_]] = Map(),
                       edgeAttributes: Map[Symbol, EdgeAttribute[_]] = Map()) {
  val all: Map[Symbol, MetaGraphEntity] =
    vertexSets ++ edgeBundles ++ vertexAttributes ++ edgeAttributes
  assert(all.size ==
    vertexSets.size + edgeBundles.size + vertexAttributes.size + edgeAttributes.size,
    "Cross type collision %s %s %s %s".format(
      vertexSets, edgeBundles, vertexAttributes, edgeAttributes))
  def ++(mds: MetaDataSet): MetaDataSet = {
    assert(
      (all.keySet & mds.all.keySet).forall(key => all(key).gUID == mds.all(key).gUID),
      "Collision: " + (all.keySet & mds.all.keySet).find(key => all(key).gUID == mds.all(key).gUID))
    return MetaDataSet(
      vertexSets ++ mds.vertexSets,
      edgeBundles ++ mds.edgeBundles,
      vertexAttributes ++ mds.vertexAttributes,
      edgeAttributes ++ mds.edgeAttributes)
  }

  def mapNames(mapping: (Symbol, Symbol)*): MetaDataSet = {
    MetaDataSet(mapping.map {
      case (from, to) => to -> all(from)
    }.toMap)
  }

  override def toString = all.toString
}
object MetaDataSet {
  def apply(all: Map[Symbol, MetaGraphEntity]): MetaDataSet = {
    MetaDataSet(
      vertexSets = all.collect { case (k, v: VertexSet) => (k, v) },
      edgeBundles = all.collect { case (k, v: EdgeBundle) => (k, v) },
      vertexAttributes = all.collect { case (k, v: VertexAttribute[_]) => (k, v) }.toMap,
      edgeAttributes = all.collect { case (k, v: EdgeAttribute[_]) => (k, v) }.toMap)
  }
  def applyWithSignature(signature: MetaGraphOperationSignature,
                         all: (Symbol, MetaGraphEntity)*): MetaDataSet = {
    var res = MetaDataSet()
    def addVS(name: Symbol, vs: VertexSet) {
      assert(signature.inputVertexSets.contains(name))
      res ++= MetaDataSet(vertexSets = Map(name -> vs))
    }
    def addEB(name: Symbol, eb: EdgeBundle) {
      val (srcName, dstName) = signature.inputEdgeBundles(name)
      res ++= MetaDataSet(edgeBundles = Map(name -> eb))
      addVS(srcName, eb.srcVertexSet)
      addVS(dstName, eb.dstVertexSet)
    }
    def addVA(name: Symbol, va: VertexAttribute[_]) {
      val vsName = signature.inputVertexAttributes(name)._1
      res ++= MetaDataSet(vertexAttributes = Map(name -> va))
      addVS(vsName, va.vertexSet)
    }
    def addEA(name: Symbol, ea: EdgeAttribute[_]) {
      val ebName = signature.inputEdgeAttributes(name)._1
      res ++= MetaDataSet(edgeAttributes = Map(name -> ea))
      addEB(ebName, ea.edgeBundle)
    }

    all.foreach {
      case (name, entity) =>
        entity match {
          case vs: VertexSet => addVS(name, vs)
          case eb: EdgeBundle => addEB(name, eb)
          case va: VertexAttribute[_] => addVA(name, va)
          case ea: EdgeAttribute[_] => addEA(name, ea)
        }
    }

    res
  }
}

// A bundle of data types.
case class DataSet(vertexSets: Map[Symbol, VertexSetData] = Map(),
                   edgeBundles: Map[Symbol, EdgeBundleData] = Map(),
                   vertexAttributes: Map[Symbol, VertexAttributeData[_]] = Map(),
                   edgeAttributes: Map[Symbol, EdgeAttributeData[_]] = Map()) {
  def metaDataSet = MetaDataSet(
    vertexSets.mapValues(_.vertexSet),
    edgeBundles.mapValues(_.edgeBundle),
    vertexAttributes.mapValues(_.vertexAttribute),
    edgeAttributes.mapValues(_.edgeAttribute))
}

class DataSetBuilder(instance: MetaGraphOperationInstance) {
  val vertexSets = mutable.Map[Symbol, VertexSetData]()
  val edgeBundles = mutable.Map[Symbol, EdgeBundleData]()
  val vertexAttributes = mutable.Map[Symbol, VertexAttributeData[_]]()
  val edgeAttributes = mutable.Map[Symbol, EdgeAttributeData[_]]()

  def toDataSet = DataSet(vertexSets.toMap, edgeBundles.toMap, vertexAttributes.toMap, edgeAttributes.toMap)

  def putVertexSet(name: Symbol, rdd: VertexSetRDD): DataSetBuilder = {
    vertexSets(name) = new VertexSetData(instance.entities.vertexSets(name), rdd)
    this
  }
  def putEdgeBundle(name: Symbol, rdd: EdgeBundleRDD): DataSetBuilder = {
    edgeBundles(name) = new EdgeBundleData(instance.entities.edgeBundles(name), rdd)
    this
  }
  def putVertexAttribute[T: TypeTag](name: Symbol, rdd: AttributeRDD[T]): DataSetBuilder = {
    val vertexAttribute = instance.entities.vertexAttributes(name).runtimeSafeCast[T]
    vertexAttributes(name) = new VertexAttributeData[T](vertexAttribute, rdd)
    this
  }
  def putEdgeAttribute[T: TypeTag](name: Symbol, rdd: AttributeRDD[T]): DataSetBuilder = {
    val edgeAttribute = instance.entities.edgeAttributes(name).runtimeSafeCast[T]
    edgeAttributes(name) = new EdgeAttributeData[T](edgeAttribute, rdd)
    this
  }
}
