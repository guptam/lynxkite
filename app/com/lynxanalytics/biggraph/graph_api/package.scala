// Package-level types and type aliases.
package com.lynxanalytics.biggraph

import com.lynxanalytics.biggraph.spark_util.{ SortedRDD, UniqueSortedRDD }

package object graph_api {
  type ID = Long

  type VertexSetRDD = UniqueSortedRDD[ID, Unit]

  type AttributeRDD[T] = UniqueSortedRDD[ID, T]

  type NonUniqueAttributeRDD[T] = SortedRDD[ID, T]

  case class Edge(src: ID, dst: ID) extends Ordered[Edge] {
    def compare(other: Edge) =
      if (src != other.src) src.compare(other.src) else dst.compare(other.dst)
  }

  type EdgeBundleRDD = UniqueSortedRDD[ID, Edge]
}
