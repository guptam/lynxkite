// Creates a new attribute by evaluating a JavaScript expression over other attributes.
package com.lynxanalytics.biggraph.graph_operations

import org.apache.commons.lang.ClassUtils
import scala.reflect.runtime.universe._

import com.lynxanalytics.biggraph.JavaScript
import com.lynxanalytics.biggraph.graph_api._

object DeriveJS {
  class Input(attrCount: Int)
      extends MagicInputSignature {
    val vs = vertexSet
    val attrs = (0 until attrCount).map(i => vertexAttribute[JSValue](vs, Symbol("attr-" + i)))
  }
  class Output[T: TypeTag](implicit instance: MetaGraphOperationInstance,
                           inputs: Input) extends MagicOutput(instance) {
    val attr = vertexAttribute[T](inputs.vs.entity)
  }
  def negative(x: Attribute[Double])(implicit manager: MetaGraphManager): Attribute[Double] = {
    import Scripting._
    val op = DeriveJSDouble(JavaScript("-x"), Seq("x"), Seq())
    op(op.attrs, Seq(x).map(VertexAttributeToJSValue.run[Double])).result.attr
  }

  def deriveFromAttributes[T: TypeTag](
    exprString: String,
    namedAttributes: Seq[(String, Attribute[_])],
    namedScalars: Seq[(String, Scalar[_])],
    vertexSet: VertexSet)(implicit manager: MetaGraphManager): Output[T] = {

    val js = JavaScript(exprString)

    // Good to go, let's prepare the attributes for DeriveJS.
    val jsValueAttributes =
      namedAttributes.map { case (_, attr) => VertexAttributeToJSValue.run(attr) }

    val op: DeriveJS[T] =
      if (typeOf[T] =:= typeOf[String]) {
        DeriveJSString(js, namedAttributes.map(_._1), namedScalars.map(_._1)).asInstanceOf[DeriveJS[T]]
      } else if (typeOf[T] =:= typeOf[Double]) {
        DeriveJSDouble(js, namedAttributes.map(_._1), namedScalars.map(_._1)).asInstanceOf[DeriveJS[T]]
      } else ???

    val defaultAttributeValues =
      namedAttributes.map { case (_, attr) => JSValue.defaultValue(attr.typeTag).value }
    val defaultScalarValues =
      namedScalars.map { case (_, attr) => JSValue.defaultValue(attr.typeTag).value }
    op.validateJS[T](defaultAttributeValues, defaultScalarValues)

    import Scripting._
    op(op.vs, vertexSet)(op.attrs, jsValueAttributes).result
  }
}
import DeriveJS._
abstract class DeriveJS[T](
  expr: JavaScript,
  attrNames: Seq[String],
  scalarNames: Seq[String])
    extends TypedMetaGraphOp[Input, Output[T]] {
  implicit def resultTypeTag: TypeTag[T]
  override val isHeavy = true
  @transient override lazy val inputs = new Input(attrNames.size)
  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(resultTypeTag, instance, inputs)

  // Validate JS using default values for the types of the attributes.
  def validateJS[T: TypeTag](
    defaultAttributeValues: Seq[Any],
    defaultScalarValues: Seq[Any]): Unit = {
    val testNamedValues =
      (attrNames ++ scalarNames).zip(defaultAttributeValues ++ defaultScalarValues).toMap
    val result = expr.evaluate(testNamedValues)
    if (result != null) {
      val converted =
        try convert(expr.evaluate(testNamedValues))
        catch { case t: Throwable => t }
      val classOfResult = ClassUtils.primitiveToWrapper(converted.getClass)
      val classOfT = ClassUtils.primitiveToWrapper(
        RuntimeSafeCastable.classTagFromTypeTag[T].runtimeClass)
      assert(classOfResult == classOfT, s"Cannot convert $result to $classOfT")
    }
  }

  def execute(inputDatas: DataSet,
              o: Output[T],
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas
    val joined = {
      val noAttrs = inputs.vs.rdd.mapValues(_ => new Array[JSValue](attrNames.size))
      inputs.attrs.zipWithIndex.foldLeft(noAttrs) {
        case (rdd, (attr, idx)) =>
          rdd.sortedJoin(attr.rdd).mapValues {
            case (attrs, attr) => {
              attrs(idx) = attr
              attrs
            }
          }
      }
    }
    val derived = joined.flatMapOptionalValues {
      case values =>
        val namedValues = attrNames.zip(values).toMap.mapValues(_.value)
        // JavaScript's "undefined" is returned as a Java "null".
        Option(expr.evaluate(namedValues)).map(convert(_))
    }
    output(o.attr, derived)
  }

  protected def convert(v: Any): T
}

object DeriveJSString extends OpFromJson {
  def fromJson(j: JsValue) =
    DeriveJSString(JavaScript(
      (j \ "expr").as[String]),
      (j \ "attrNames").as[Seq[String]],
      (j \ "scalarNames").as[Seq[String]])
}
case class DeriveJSString(
  expr: JavaScript,
  attrNames: Seq[String],
  scalarNames: Seq[String])
    extends DeriveJS[String](expr, attrNames, scalarNames) {
  @transient lazy val resultTypeTag = typeTag[String]
  override def toJson = Json.obj(
    "expr" -> expr.expression,
    "attrNames" -> attrNames,
    "scalarNames" -> scalarNames)
  def convert(v: Any): String = v.asInstanceOf[String]
}

object DeriveJSDouble extends OpFromJson {
  def fromJson(j: JsValue) =
    DeriveJSDouble(JavaScript((j \ "expr").as[String]),
      (j \ "attrNames").as[Seq[String]],
      (j \ "scalarNames").as[Seq[String]])
}
case class DeriveJSDouble(
  expr: JavaScript,
  attrNames: Seq[String], scalarNames: Seq[String])
    extends DeriveJS[Double](expr, attrNames, scalarNames) {
  @transient lazy val resultTypeTag = typeTag[Double]
  override def toJson = Json.obj(
    "expr" -> expr.expression,
    "attrNames" -> attrNames,
    "scalarNames" -> scalarNames)
  def convert(v: Any): Double = v match {
    case v: Int => v // Convert ints to doubles.
    case v: Double => v
  }
}
