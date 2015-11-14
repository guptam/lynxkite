// An RDD where each partition is locally sorted.
//
// When two RDDs are co-partitioned, this allows for a faster join
// implementation. (About 10x speedup over the usual join.) Some other
// operations also benefit.
//
// This class also includes some useful methods missing from the stock
// PairRDDFunctions, such as mapValuesWithKeys and fast prefix sampling.
//
// All MetaGraphEntities are stored as SortedRDDs.
//
// As a further optimization, ArrayBackedSortedRDD stores each partition in one
// array. These RDDs can be restricted to a subset of IDs via a fast binary
// search.

package com.lynxanalytics.biggraph.spark_util

import org.apache.spark.{ HashPartitioner, Partition, Partitioner, TaskContext }
import org.apache.spark.rdd._
import scala.collection.mutable.ArrayBuffer
import scala.math.Ordering
import scala.reflect.ClassTag

import com.lynxanalytics.biggraph.spark_util.Implicits._

object SortedRDD {
  // Creates a SortedRDD from an unsorted but partitioned RDD.
  def fromUnsorted[K: Ordering, V](rdd: RDD[(K, V)]): SortedRDD[K, V] = {
    val arrayRDD = new SortedArrayRDD(rdd, needsSorting = true)
    new ArrayBackedSortedRDD(arrayRDD)
  }
  // Creates a SortedRDD from an unsorted but partitioned RDD.
  def fromUniqueUnsorted[K: Ordering, V](rdd: RDD[(K, V)]): UniqueSortedRDD[K, V] = {
    val arrayRDD = new SortedArrayRDD(rdd, needsSorting = true)
    new ArrayBackedSortedRDD(arrayRDD) with UniqueSortedRDD[K, V]
  }
}

private object SortedRDDUtil {
  def assertMatchingRDDs[K](first: SortedRDD[K, _], second: SortedRDD[K, _]): Unit = {
    assert(
      first.partitioner.get eq second.partitioner.get,
      s"Partitioner mismatch between $first and $second")
  }

  // Keys in bi2 must be unique.
  def merge[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (V, W))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next;
        (k1, (v1, v2)) #:: merge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        merge(bi1, bi2)
      } else {
        bi2.next
        merge(bi1, bi2)
      }
    } else {
      Stream()
    }
  }

  // Keys in bi1 and bi2 must be unique.
  def mergeUnique[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (V, W))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next;
        bi2.next;
        (k1, (v1, v2)) #:: merge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        merge(bi1, bi2)
      } else {
        bi2.next
        merge(bi1, bi2)
      }
    } else {
      Stream()
    }
  }

  private class ValuesForKeyIterator[K, V](k: K, bi: collection.BufferedIterator[(K, V)])
      extends Iterator[V] {
    def hasNext = bi.hasNext && bi.head._1 == k
    def next = bi.next._2
  }

  // This one supports duplicates on both sides. It is 2-3 times slower.
  def mergeWithDuplicates[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (V, W))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        val span1 = new ValuesForKeyIterator(k1, bi1).toStream
        val span2 = new ValuesForKeyIterator(k1, bi2).toStream
        val cross = for (v1 <- span1; v2 <- span2) yield (k1, (v1, v2))
        cross #::: mergeWithDuplicates(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        mergeWithDuplicates(bi1, bi2)
      } else {
        bi2.next
        mergeWithDuplicates(bi1, bi2)
      }
    } else {
      Stream()
    }
  }

  // Keys in bi2 must be unique.
  def leftOuterMerge[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (V, Option[W]))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next;
        (k1, (v1, Some(v2))) #:: leftOuterMerge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        (k1, (v1, None)) #:: leftOuterMerge(bi1, bi2)
      } else {
        bi2.next
        leftOuterMerge(bi1, bi2)
      }
    } else if (bi1.hasNext) {
      bi1.toStream.map { case (k, v) => (k, (v, None)) }
    } else {
      Stream()
    }
  }

  // Keys in bi2 must be unique.
  def uniqueLeftOuterMerge[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (V, Option[W]))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next;
        bi2.next;
        (k1, (v1, Some(v2))) #:: leftOuterMerge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        (k1, (v1, None)) #:: leftOuterMerge(bi1, bi2)
      } else {
        bi2.next
        leftOuterMerge(bi1, bi2)
      }
    } else if (bi1.hasNext) {
      bi1.toStream.map { case (k, v) => (k, (v, None)) }
    } else {
      Stream()
    }
  }

  // Keys in both inputs must be unique.
  def fullOuterMerge[K, V, W](
    bi1: collection.BufferedIterator[(K, V)],
    bi2: collection.BufferedIterator[(K, W)])(implicit ord: Ordering[K]): Stream[(K, (Option[V], Option[W]))] = {
    if (bi1.hasNext && bi2.hasNext) {
      val (k1, v1) = bi1.head
      val (k2, v2) = bi2.head
      if (ord.equiv(k1, k2)) {
        bi1.next;
        bi2.next;
        (k1, (Some(v1), Some(v2))) #:: fullOuterMerge(bi1, bi2)
      } else if (ord.lt(k1, k2)) {
        bi1.next
        (k1, (Some(v1), None)) #:: fullOuterMerge(bi1, bi2)
      } else {
        bi2.next
        (k2, (None, Some(v2))) #:: fullOuterMerge(bi1, bi2)
      }
    } else if (bi1.hasNext) {
      bi1.toStream.map { case (k, v) => (k, (Some(v), None)) }
    } else if (bi2.hasNext) {
      bi2.toStream.map { case (k, v) => (k, (None, Some(v))) }
    } else {
      Stream()
    }
  }
}

sealed trait SortedRDDRecipe[K, V] {
  def asGeneral: SortedRDD[K, V]
  def assertUnique: UniqueSortedRDD[K, V]
}
class DerivedSortedRDDRecipe[K: Ordering, VOld, VNew](
    base: SortedRDD[K, VOld],
    derivation: DerivedSortedRDD.Derivation[K, VOld, VNew]) extends SortedRDDRecipe[K, VNew] {
  def asGeneral = new DerivedSortedRDD(base, derivation)
  def assertUnique = new DerivedSortedRDD(base, derivation) with UniqueSortedRDD[K, VNew]
}
class BiDerivedSortedRDDRecipe[K: Ordering, VOld1, VOld2, VNew](
  base1: SortedRDD[K, VOld1],
  base2: SortedRDD[K, VOld2],
  derivation: BiDerivedSortedRDD.Derivation[K, VOld1, VOld2, VNew])
    extends SortedRDDRecipe[K, VNew] {

  def asGeneral = new BiDerivedSortedRDD(base1, base2, derivation)
  def assertUnique = new BiDerivedSortedRDD(base1, base2, derivation) with UniqueSortedRDD[K, VNew]
}
class RestrictedArrayBackedSortedRDDRecipe[K: Ordering, V](
    arrayRDD: SortedArrayRDD[K, V],
    ids: IndexedSeq[K]) extends SortedRDDRecipe[K, V] {
  def asGeneral = new RestrictedArrayBackedSortedRDD(arrayRDD, ids)
  def assertUnique = new RestrictedArrayBackedSortedRDD(arrayRDD, ids) with UniqueSortedRDD[K, V]
}

// An RDD with each partition sorted by the key. "self" must already be sorted.
abstract class SortedRDD[K, V] private[spark_util] (val self: RDD[(K, V)])(
    // We need to make this implicit explicit so that it's visible from SortedRDD's subtraits.
    implicit val kOrder: Ordering[K]) extends RDD[(K, V)](self) {

  assert(
    self.partitioner.isDefined,
    s"$self was used to create a SortedRDD, but it wasn't partitioned")
  override def getPartitions: Array[Partition] = self.partitions
  override val partitioner = self.partitioner
  override def compute(split: Partition, context: TaskContext) = self.iterator(split, context)

  // TODO: derive and biDeriveWith will go away. It's kept now so that I don't have to change
  // everything yet.
  // See comments at DerivedSortedRDD before blindly using this method!
  private def derive[R](derivation: DerivedSortedRDD.Derivation[K, V, R]) =
    new DerivedSortedRDD(this, derivation)

  private def deriveRecipe[R](derivation: DerivedSortedRDD.Derivation[K, V, R]) =
    new DerivedSortedRDDRecipe(this, derivation)

  // See comments at DerivedSortedRDD before blindly using this method!
  private def biDeriveWith[W, R](
    other: SortedRDD[K, W],
    derivation: BiDerivedSortedRDD.Derivation[K, V, W, R]) =
    new BiDerivedSortedRDD(this, other, derivation)

  protected def biDeriveWithRecipe[W, R](
    other: SortedRDD[K, W],
    derivation: BiDerivedSortedRDD.Derivation[K, V, W, R]) =
    new BiDerivedSortedRDDRecipe(this, other, derivation)

  // Differs from Spark's join implementation as this allows multiple keys only on the left side.
  // The keys of 'other' must be unique!
  def sortedJoin[W](
    other: UniqueSortedRDD[K, W]): SortedRDD[K, (V, W)] =
    new BiDerivedSortedRDDRecipe[K, V, W, (V, W)](
      this,
      other,
      { (first, second) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.merge(it1.buffered, it2.buffered).iterator
        }
      }).asGeneral

  // A version of sortedJoin that handles duplicates on both sides. This means the result can be
  // (much) larger than the input, as all combinations are enumerated for the duplicates.
  // It is 2-3 times slower than sortedJoin for the same data.
  def sortedJoinWithDuplicates[W](other: SortedRDD[K, W]): SortedRDD[K, (V, W)] =
    biDeriveWith[W, (V, W)](
      other,
      { (first, second) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.mergeWithDuplicates(it1.buffered, it2.buffered).iterator
        }
      })

  // Differs from Spark's join implementation as this allows multiple keys only on the left side.
  // The keys of 'other' must be unique!
  def sortedLeftOuterJoin[W](other: UniqueSortedRDD[K, W]): SortedRDD[K, (V, Option[W])] =
    biDeriveWithRecipe[W, (V, Option[W])](
      other,
      { (first, second) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.leftOuterMerge(it1.buffered, it2.buffered).iterator
        }
      }).asGeneral

  def mapValues[U](f: V => U)(implicit ck: ClassTag[K], cv: ClassTag[V]): SortedRDD[K, U] =
    mapValuesRecipe(f).asGeneral

  def mapValuesRecipe[U](f: V => U)(implicit ck: ClassTag[K], cv: ClassTag[V]): SortedRDDRecipe[K, U] =
    deriveRecipe(_.self.mapValues(x => f(x)))

  def flatMapValues[U](
    f: V => TraversableOnce[U])(
      implicit ck: ClassTag[K], cv: ClassTag[V]): SortedRDD[K, U] =
    flatMapValuesRecipe(f).asGeneral

  def flatMapValuesRecipe[U](
    f: V => TraversableOnce[U])(
      implicit ck: ClassTag[K], cv: ClassTag[V]): SortedRDDRecipe[K, U] =
    deriveRecipe(_.self.flatMapValues(x => f(x)))

  // This version takes a Key-Value tuple as argument.
  def mapValuesWithKeys[U](f: ((K, V)) => U): SortedRDD[K, U] =
    mapValuesWithKeysRecipe(f).asGeneral

  def mapValuesWithKeysRecipe[U](f: ((K, V)) => U): SortedRDDRecipe[K, U] =
    deriveRecipe(_.mapPartitions(
      { it => it.map { case (k, v) => (k, f(k, v)) } },
      preservesPartitioning = true))

  override def filter(f: ((K, V)) => Boolean): SortedRDD[K, V] =
    filterRecipe(f).asGeneral

  def filterRecipe(f: ((K, V)) => Boolean): SortedRDDRecipe[K, V] =
    deriveRecipe(_.self.filter(f))

  override def distinct: UniqueSortedRDD[K, V] =
    // TODO: use keys and not key-value pairs for enforcing distinction
    deriveRecipe(
      _.mapPartitions({ it =>
        val bi = it.buffered
        new Iterator[(K, V)] {
          def hasNext = bi.hasNext
          def next() = {
            val n = bi.next
            while (bi.hasNext && bi.head == n) bi.next
            n
          }
        }
      }, preservesPartitioning = true)).assertUnique

  // `createCombiner`, which turns a V into a C (e.g., creates a one-element list)
  // `mergeValue`, to merge a V into a C (e.g., adds it to the end of a list)
  def combineByKey[C](createCombiner: V => C,
                      mergeValue: (C, V) => C): UniqueSortedRDD[K, C] =
    deriveRecipe(
      _.mapPartitions({ it =>
        val bi = it.buffered
        new Iterator[(K, C)] {
          def hasNext = bi.hasNext
          def next() = {
            val n = bi.next
            var res = createCombiner(n._2)
            while (bi.hasNext && bi.head._1 == n._1) res = mergeValue(res, bi.next._2)
            n._1 -> res
          }
        }
      }, preservesPartitioning = true)).assertUnique

  def groupByKey(): UniqueSortedRDD[K, ArrayBuffer[V]] = {
    val createCombiner = (v: V) => ArrayBuffer(v)
    val mergeValue = (buf: ArrayBuffer[V], v: V) => buf += v
    combineByKey(createCombiner, mergeValue)
  }

  // Concrete implementing classes should override this one.
  def restrictToIdSetRecipe(ids: IndexedSeq[K]): SortedRDDRecipe[K, V]

  // The ids seq needs to be sorted.
  def restrictToIdSet(ids: IndexedSeq[K]): SortedRDD[K, V] = restrictToIdSetRecipe(ids).asGeneral

  def cacheBackingArray(): Unit
}

trait UniqueSortedRDD[K, V] extends SortedRDD[K, V] {
  override def sortedJoin[W](other: UniqueSortedRDD[K, W]): UniqueSortedRDD[K, (V, W)] = {
    new BiDerivedSortedRDDRecipe[K, V, W, (V, W)](
      this,
      other, { (first, second) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.mergeUnique(it1.buffered, it2.buffered).iterator // TODO
        }
      }).assertUnique
  }

  // Returns an RDD with the union of the keyset of this and other. For each key it returns two
  // Options with the value for the key in this and in the other RDD.
  def fullOuterJoin[W](other: UniqueSortedRDD[K, W]): UniqueSortedRDD[K, (Option[V], Option[W])] =
    new BiDerivedSortedRDDRecipe(
      this,
      other,
      { (first: SortedRDD[K, V], second: SortedRDD[K, W]) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.fullOuterMerge(it1.buffered, it2.buffered).iterator
        }
      }).assertUnique

  override def sortedLeftOuterJoin[W](other: UniqueSortedRDD[K, W]): UniqueSortedRDD[K, (V, Option[W])] =
    biDeriveWithRecipe[W, (V, Option[W])](
      other,
      { (first, second) =>
        SortedRDDUtil.assertMatchingRDDs(first, second)
        first.zipPartitions(second, preservesPartitioning = true) { (it1, it2) =>
          SortedRDDUtil.uniqueLeftOuterMerge(it1.buffered, it2.buffered).iterator
        }
      }).assertUnique

  // The ids seq needs to be sorted.
  override def restrictToIdSet(ids: IndexedSeq[K]): UniqueSortedRDD[K, V] =
    restrictToIdSetRecipe(ids).assertUnique

  override def mapValues[U](f: V => U)(implicit ck: ClassTag[K], cv: ClassTag[V]): UniqueSortedRDD[K, U] =
    mapValuesRecipe(f).assertUnique

  override def filter(f: ((K, V)) => Boolean): UniqueSortedRDD[K, V] =
    filterRecipe(f).assertUnique

  override def mapValuesWithKeys[U](f: ((K, V)) => U): UniqueSortedRDD[K, U] =
    mapValuesWithKeysRecipe(f).assertUnique

  def flatMapOptionalValues[U](
    f: V => Option[U])(
      implicit ck: ClassTag[K], cv: ClassTag[V]): UniqueSortedRDD[K, U] = {
    flatMapValuesRecipe { x => f(x) }.assertUnique
  }

}

// SortedRDD which was derived from one other sorted rdd without changing the id space.
// Also, the derivation must be such that the value of an item (k, v) in the result only depends
// on the value of items (k, ?) in the input. E.g. it can not depend on the ordinal of (k, ?)s in
// the input or values of other items.
// With other words, we assume that the order filter by id and derivation are exchangeable.
private[spark_util] object DerivedSortedRDD {
  type Derivation[K, VOld, VNew] = SortedRDD[K, VOld] => RDD[(K, VNew)]
}
private[spark_util] class DerivedSortedRDD[K: Ordering, VOld, VNew](
  source: SortedRDD[K, VOld],
  derivation: DerivedSortedRDD.Derivation[K, VOld, VNew])
    extends SortedRDD[K, VNew](derivation(source)) {

  def restrictToIdSetRecipe(ids: IndexedSeq[K]): SortedRDDRecipe[K, VNew] =
    new DerivedSortedRDDRecipe(source.restrictToIdSet(ids), derivation)

  def cacheBackingArray(): Unit =
    source.cacheBackingArray()
}

// SortedRDD which was derived from two other sorted rdds without changing the id space.
// See comments at DerivedSortedRDD, the same restrictions apply here.
private[spark_util] object BiDerivedSortedRDD {
  type Derivation[K, VOld1, VOld2, VNew] = (SortedRDD[K, VOld1], SortedRDD[K, VOld2]) => RDD[(K, VNew)]
}
private[spark_util] class BiDerivedSortedRDD[K: Ordering, VOld1, VOld2, VNew](
  source1: SortedRDD[K, VOld1],
  source2: SortedRDD[K, VOld2],
  derivation: BiDerivedSortedRDD.Derivation[K, VOld1, VOld2, VNew])
    extends SortedRDD[K, VNew](derivation(source1, source2)) {

  def restrictToIdSetRecipe(ids: IndexedSeq[K]): SortedRDDRecipe[K, VNew] =
    new BiDerivedSortedRDDRecipe(
      source1.restrictToIdSet(ids), source2.restrictToIdSet(ids), derivation)

  def cacheBackingArray(): Unit = {
    source1.cacheBackingArray()
    source2.cacheBackingArray()
  }
}

// An RDD with each partition having a single value, an x: (Int, Array[(K, V)]). The x._1 is simply
// the partition id. x._2 is an array of key-value pairs sorted by key. data is assumed to be
// partitioned by a hash partitioner. A (k,v) in partition i of data will end up in the array in
// partition i of this SortedArrayRDD.
private[spark_util] class SortedArrayRDD[K: Ordering, V](data: RDD[(K, V)], needsSorting: Boolean)
    extends RDD[(Int, Array[(K, V)])](data) {

  assert(
    data.partitioner.isDefined,
    s"$data was used to create a SortedArrayRDD, but it wasn't partitioned")

  assert(
    data.partitioner.get.isInstanceOf[HashPartitioner],
    s"$data was used to create a SortedArrayRDD, but it wasn't partitioned via a HashPartitioner")

  override def getPartitions: Array[Partition] = data.partitions
  override val partitioner = data.partitioner
  override def compute(split: Partition, context: TaskContext): Iterator[(Int, Array[(K, V)])] = {
    val it = data.iterator(split, context)
    val array = it.toArray
    if (needsSorting) Sorting.quickSort(array)(Ordering.by[(K, V), K](_._1))
    Iterator((split.index, array))
  }
}

// "Trust me, this RDD is partitioned with this partitioner."
private[spark_util] class AlreadyPartitionedRDD[T: ClassTag](data: RDD[T], p: Partitioner)
    extends RDD[T](data) {
  assert(p.numPartitions == data.partitions.size, s"Mismatched partitioner: $p")
  override val partitioner = Some(p)
  override def getPartitions: Array[Partition] = data.partitions
  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    data.iterator(split, context)
  }
}

// "Trust me, this RDD is already sorted."
private[spark_util] class AlreadySortedRDD[K: Ordering, V](data: RDD[(K, V)])
    extends SortedRDD[K, V](data) {
  // Normal operations run on the iterators. Arrays are only created when necessary.
  lazy val arrayRDD = new SortedArrayRDD(data, needsSorting = false)
  def restrictToIdSetRecipe(ids: IndexedSeq[K]): SortedRDDRecipe[K, V] =
    new RestrictedArrayBackedSortedRDDRecipe(arrayRDD, ids)
  def cacheBackingArray(): Unit =
    arrayRDD.cache()
}

private[spark_util] class ArrayBackedSortedRDD[K: Ordering, V](arrayRDD: SortedArrayRDD[K, V])
    extends SortedRDD[K, V](
      arrayRDD.mapPartitions(
        it => it.next._2.iterator,
        preservesPartitioning = true)) {
  def restrictToIdSetRecipe(ids: IndexedSeq[K]): SortedRDDRecipe[K, V] =
    new RestrictedArrayBackedSortedRDDRecipe(arrayRDD, ids)

  def cacheBackingArray(): Unit =
    arrayRDD.cache()

  override def setName(newName: String): this.type = {
    name = newName
    arrayRDD.name = s"Backing ArrayRDD of $newName"
    this
  }
}
private object BinarySearchUtils {
  // Finds the range in a sorted array where all ids == a given id, assuming the range must be
  // in the given [startIdx, endIdx) range.
  // If the id is not found in the array, it returns an empty range with elements to the left
  // of the range being strictly less while elements to the right being strictly more than id.
  // Both input and output start indices are inclusive, end indices are exclusive.
  def findRange[K: Ordering, V](
    array: Array[(K, V)], id: K, startIdx: Int, endIdx: Int): (Int, Int) = {

    val ord = implicitly[Ordering[K]]
    import ord.mkOrderingOps

    if (startIdx == endIdx) (startIdx, endIdx)
    else {
      val midIdx = (startIdx + endIdx) / 2
      val midId = array(midIdx)._1
      if (midId < id) {
        findRange(array, id, midIdx + 1, endIdx)
      } else if (midId > id) {
        findRange(array, id, startIdx, midIdx)
      } else {
        // TODO(xandrew): replace the below code with binary search to retain speed for
        // huge blocks of identical ids.
        var resStartIdx = midIdx
        // Let's find the first element equal to id.
        while ((resStartIdx > startIdx) && (array(resStartIdx - 1)._1 == id)) resStartIdx -= 1
        var resEndIdx = midIdx
        // Let's find the first element greater than id.
        while ((resEndIdx < endIdx) && (array(resEndIdx)._1 == id)) resEndIdx += 1
        (resStartIdx, resEndIdx)
      }
    }
  }

  // The ids seq needs to be sorted.
  def findIds[K: Ordering, V](
    array: Array[(K, V)], ids: IndexedSeq[K], startIdx: Int, endIdx: Int): Iterator[(K, V)] = {

    if (ids.size == 0) Iterator()
    else {
      val midIdIdx = ids.size / 2
      val midId = ids(midIdIdx)
      val midRange = findRange(array, midId, startIdx, endIdx)
      findIds(array, ids.slice(0, midIdIdx), startIdx, midRange._1) ++
        array.view(midRange._1, midRange._2).iterator ++
        findIds(array, ids.slice(midIdIdx + 1, ids.size), midRange._2, endIdx)
    }
  }
}

object RestrictedArrayBackedSortedRDD {
  def restrictArrayRDDToIds[K: Ordering, V](
    arrayRDD: SortedArrayRDD[K, V],
    ids: IndexedSeq[K]): RDD[(K, V)] = {
    val partitioner = arrayRDD.partitioner.get
    arrayRDD.mapPartitions(
      { it =>
        val (pid, array) = it.next
        val idsInThisPartition = ids.filter(id => partitioner.getPartition(id) == pid)
        BinarySearchUtils.findIds[K, V](array, idsInThisPartition, 0, array.size)
      },
      preservesPartitioning = true)
  }
}
private[spark_util] class RestrictedArrayBackedSortedRDD[K: Ordering, V](
  arrayRDD: SortedArrayRDD[K, V],
  ids: IndexedSeq[K])
    extends SortedRDD[K, V](
      RestrictedArrayBackedSortedRDD.restrictArrayRDDToIds(arrayRDD, ids)) {
  def restrictToIdSetRecipe(newIds: IndexedSeq[K]): SortedRDDRecipe[K, V] =
    new RestrictedArrayBackedSortedRDDRecipe(arrayRDD, ids.intersect(newIds))

  def cacheBackingArray: Unit =
    arrayRDD.cache()
}
