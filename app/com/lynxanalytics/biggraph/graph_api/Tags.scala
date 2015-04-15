// Tags are a small in-memory filesystem that stores projects as directories.
package com.lynxanalytics.biggraph.graph_api

import java.util.UUID
import play.api.libs.json
import scala.collection.mutable

class SymbolPath(val path: Iterable[Symbol]) extends Iterable[Symbol] with Ordered[SymbolPath] {
  def /(name: Symbol): SymbolPath = path.toSeq :+ name
  def /(suffixPath: SymbolPath): SymbolPath = path ++ suffixPath
  override def toString = path.map(_.name).mkString("/")
  def iterator = path.iterator
  def parent: SymbolPath = path.init
  def name = path.last
  def compare(other: SymbolPath) = toString compare other.toString
}
object SymbolPath {
  import scala.language.implicitConversions
  implicit def fromIterable(sp: Iterable[Symbol]): SymbolPath = new SymbolPath(sp)
  implicit def fromString(str: String): SymbolPath =
    str.split("/", -1).toSeq.map(Symbol(_))
  implicit def fromSymbol(sym: Symbol): SymbolPath = fromString(sym.name)
}

sealed trait TagPath extends Serializable with Ordered[TagPath] {
  val name: Symbol
  val parent: TagDir

  def fullName: SymbolPath = parent.fullName / name

  def compare(other: TagPath) = fullName compare other.fullName

  def rm() = parent.rmChild(name)

  def isOffspringOf(other: TagPath): Boolean =
    (other == this) || this.parent.isOffspringOf(other)

  def clone(newParent: TagDir, newName: Symbol): TagPath

  def followPath(names: Iterable[Symbol]): Option[TagPath]

  def allTags: Iterable[Tag]

  // A string snapshot of the full directory structure. Mostly for debugging.
  def lsRec(indent: Int = 0): String
}
object TagPath {
  import scala.language.implicitConversions
  implicit def asDir(path: TagPath) = path.asInstanceOf[TagDir]
  implicit def asTag(path: TagPath) = path.asInstanceOf[Tag]
}

final case class Tag(name: Symbol, parent: TagDir, content: String) extends TagPath {
  def clone(newParent: TagDir, newName: Symbol): Tag = newParent.addTag(newName, content)
  def followPath(names: Iterable[Symbol]): Option[TagPath] =
    if (names.nonEmpty) None else Some(this)
  def allTags = Seq(this)
  def lsRec(indent: Int = 0): String = " " * indent + fullName + " => " + content + "\n"
  def gUID: UUID = UUID.fromString(content)
}

trait TagDir extends TagPath {
  val store: KeyValueStore

  def /(subPath: SymbolPath): TagPath = {
    val p = followPath(subPath)
    assert(p.nonEmpty, s"$subPath not found in $this")
    p.get
  }

  def exists(subPath: SymbolPath) = followPath(subPath).nonEmpty
  def existsDir(subPath: SymbolPath) = followPath(subPath).exists(_.isInstanceOf[TagDir])
  def existsTag(subPath: SymbolPath) = followPath(subPath).exists(_.isInstanceOf[Tag])

  def rmChild(name: Symbol): Unit = synchronized {
    store.delete(children(name).fullName.toString)
    children -= name
  }
  def rm(offspring: SymbolPath): Unit = synchronized {
    followPath(offspring).map(_.rm())
  }
  def addTag(name: Symbol, content: String): Tag = synchronized {
    assert(!children.contains(name), s"'$this' already contains '$name'.")
    val result = Tag(name, this, content)
    store.put(result.fullName.toString, content)
    children(name) = result
    result
  }
  def setTag(path: SymbolPath, content: String): Tag = synchronized {
    assert(!existsDir(path), s"'$path' is a directory.")
    assert(path.nonEmpty, s"Cannot create path named '$path'.")
    if (existsTag(path)) rm(path)
    val dir = mkDirs(new SymbolPath(path.dropRight(1)))
    dir.addTag(path.last, content)
  }

  def mkDir(name: Symbol): TagSubDir = synchronized {
    assert(!existsTag(name), s"Tag '$name' already exists.")
    if (existsDir(name)) return (this / name).asInstanceOf[TagSubDir]
    val result = TagSubDir(name, this, store)
    children(name) = result
    result
  }
  def mkDirs(path: SymbolPath): TagDir = synchronized {
    if (path.isEmpty) this
    else mkDir(path.head).mkDirs(new SymbolPath(path.tail))
  }

  def cp(from: SymbolPath, to: SymbolPath): TagPath = synchronized {
    assert(to.nonEmpty, s"Cannot copy from '$from' to '$to'.")
    assert(!exists(to), s"'$to' already exists.")
    assert(exists(from), s"'$from' does not exist.")
    val toDir = mkDirs(to.dropRight(1))
    (this / from).clone(toDir, to.last)
  }

  def followPath(names: Iterable[Symbol]): Option[TagPath] = {
    if (names.isEmpty) Some(this)
    else children.get(names.head).flatMap(_.followPath(names.tail))
  }

  def clone(newParent: TagDir, newName: Symbol): TagSubDir = {
    assert(!newParent.isOffspringOf(this), s"'$newParent' contains '$this'.")
    val cloned = newParent.mkDir(newName)
    for ((name, child) <- children) {
      child.clone(cloned, name)
    }
    cloned
  }

  def allTags = children.values.flatMap(_.allTags)

  def lsRec(indent: Int = 0): String =
    " " * indent + fullName + "\n" + children.values.map(_.lsRec(indent + 1)).mkString

  def clear(): Unit = synchronized {
    store.deletePrefix(fullName.toString + "/")
    children.clear()
  }

  def ls: Seq[TagPath] = children.values.toSeq.sorted

  private val children = mutable.Map[Symbol, TagPath]()
}

final case class TagSubDir(name: Symbol, parent: TagDir, store: KeyValueStore) extends TagDir

final case class TagRoot(filename: String) extends TagDir {
  val name = null
  val parent = null
  val store: KeyValueStore = new SQLiteKeyValueStore(filename)
  override val fullName: SymbolPath = new SymbolPath(Seq())
  override def isOffspringOf(other: TagPath): Boolean = (other == this)
  def setTags(tags: Map[SymbolPath, String]): Unit = synchronized {
    store.transaction {
      for ((k, v) <- tags) {
        setTag(k, v)
      }
    }
  }
  setTags(TagRoot.load(store))
}
object TagRoot {
  def load(store: KeyValueStore): Map[SymbolPath, String] =
    store.scan("").map { case (k, v) => SymbolPath.fromString(k) -> v }.toMap
}
