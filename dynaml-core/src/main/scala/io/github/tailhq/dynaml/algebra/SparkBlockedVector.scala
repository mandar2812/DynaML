/*
Copyright 2016 Mandar Chandorkar

Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
* */
package io.github.tailhq.dynaml.algebra

import breeze.linalg.{DenseVector, NumericOps}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.collection.immutable.NumericRange

/**
  * @author tailhq date 13/10/2016.
  * A distributed vector that is stored in blocks.
  *
  * @param data The underlying [[RDD]] which should consist of
  *             block indices and a breeze [[DenseVector]] containing
  *             all the elements in the said block.
  */
private[dynaml] class SparkBlockedVector(data: RDD[(Long, DenseVector[Double])],
                                         num_rows: Long = -1L,
                                         num_row_blocks: Long = -1L)
  extends SparkVectorLike[DenseVector[Double]] with NumericOps[SparkBlockedVector] {

  lazy val rowBlocks = if(num_row_blocks == -1L) data.keys.max else num_row_blocks

  lazy val colBlocks = 1L

  override var vector = data

  lazy val rows: Long = if(num_rows == -1L) data.map(_._2.length).sum().toLong else num_rows

  lazy val cols: Long = 1L

  def _data = vector.sortByKey()


  override def repr: SparkBlockedVector = this

  def t: SparkBlockedDualVector = new SparkBlockedDualVector(data.map(c => (c._1, c._2.t)), rows, rowBlocks)

  def apply(r: NumericRange[Long]): SparkBlockedVector = {

    new SparkBlockedVector(
      data.filter(e => r.contains(e._1))
        .map(e => (e._1 - r.min, e._2)),
      num_row_blocks = r.length
    )
  }


  def persist: Unit = {
    data.persist(StorageLevel.MEMORY_AND_DISK)
  }

  def unpersist: Unit = {
    data.unpersist()
  }


}

object SparkBlockedVector {

  /**
    * Create a [[SparkBlockedVector]] from a [[SparkVector]], this
    * method takes the underlying key-value [[RDD]] and groups it
    * by blocks converting each block to a breeze [[DenseVector]]
    *
    * @param v The distributed vector
    * @param numElementsRowBlock Maximum number of rows in each block
    *
    */
  def apply(v: SparkVector, numElementsRowBlock: Int): SparkBlockedVector = {
    new SparkBlockedVector(
      v._vector.map(e => (e._1/numElementsRowBlock,e)).groupByKey().map(b => {
        val (blocIndex, locData) = (b._1, b._2.map(cp => (cp._1 - b._1*numElementsRowBlock,cp._2)).toMap)
        (blocIndex, DenseVector.tabulate[Double](locData.size)(i => locData(i)))
      })
    )
  }

  /**
    * Vertically merge a number of partitioned vectors.
    */
  def vertcat(vectors: SparkBlockedVector*): SparkBlockedVector = {
    //sanity check
    assert(vectors.map(_.colBlocks).distinct.length == 1,
      "In case of vertical concatenation of matrices their columns sizes must be equal")

    val sizes = vectors.map(_.rowBlocks)
    new SparkBlockedVector(vectors.zipWithIndex.map(couple => {
      val offset = sizes.slice(0, couple._2).sum
      couple._1._data.map(c => (c._1+offset, c._2))
    }).reduceLeft((a,b) => a.union(b)))
  }

}