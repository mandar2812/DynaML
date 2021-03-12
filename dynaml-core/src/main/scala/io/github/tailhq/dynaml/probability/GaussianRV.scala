/*
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
package io.github.tailhq.dynaml.probability

import breeze.linalg.{DenseMatrix, DenseVector}
import breeze.stats.distributions.{ContinuousDistr, Moments}
import io.github.tailhq.dynaml.algebra.{PartitionedPSDMatrix, PartitionedVector}
import io.github.tailhq.dynaml.analysis.{PartitionedVectorField, VectorField}
import io.github.tailhq.dynaml.pipes.DataPipe
import io.github.tailhq.dynaml.probability.distributions._
import spire.algebra.Field

/**
  * <h3>Gaussian Random Variable</h3>
  * */
abstract class AbstractGaussianRV[
T, V, Distr <: ContinuousDistr[T] with Moments[T, V] with HasErrorBars[T]] extends
  ContinuousRVWithDistr[T, Distr] {

  override val sample = DataPipe(() => underlyingDist.sample())
}

/**
  * Univariate gaussian random variable
  * @author tailhq on 26/7/16.
  * */
case class GaussianRV(mu: Double, sigma: Double) extends
  AbstractGaussianRV[Double, Double, UnivariateGaussian] {
  override val underlyingDist = new UnivariateGaussian(mu, sigma)
}

/**
  * Multivariate gaussian random variable
  * @author tailhq
  * */
case class MultGaussianRV(
  mu: DenseVector[Double],
  covariance: DenseMatrix[Double])(
  implicit ev: Field[DenseVector[Double]])
  extends AbstractGaussianRV[DenseVector[Double], DenseMatrix[Double], MVGaussian] {

  override val underlyingDist = MVGaussian(mu, covariance)

  def apply(r: Range): MultGaussianRV = MultGaussianRV(mu(r), covariance(r,r))

}

object MultGaussianRV {

  def apply(num_dim: Int)(mu: DenseVector[Double], covariance: DenseMatrix[Double]) = {
    assert(
      num_dim == mu.length,
      "Number of dimensions of vector space must match the number of elements of mean")

    implicit val ev = VectorField(num_dim)

    new MultGaussianRV(mu, covariance)
  }
}

/**
  * Multivariate blocked gaussian random variable
  * @author tailhq
  * */
case class MultGaussianPRV(
  mu: PartitionedVector,
  covariance: PartitionedPSDMatrix)(
  implicit ev: Field[PartitionedVector])
  extends AbstractGaussianRV[PartitionedVector, PartitionedPSDMatrix, BlockedMultiVariateGaussian] {

  override val underlyingDist: BlockedMultiVariateGaussian = BlockedMultiVariateGaussian(mu, covariance)

}

object MultGaussianPRV {

  def apply(num_dim: Long, nE: Int)(mu: PartitionedVector, covariance: PartitionedPSDMatrix) = {
    assert(
      num_dim == mu.rows,
      "Number of dimensions of vector space must match the number of elements of mean")

    implicit val ev = PartitionedVectorField(num_dim, nE)

    new MultGaussianPRV(mu, covariance)
  }

}

/**
  * Matrix gaussian random variable
  * */
case class MatrixNormalRV(
  m: DenseMatrix[Double], u: DenseMatrix[Double],
  v: DenseMatrix[Double]) extends AbstractGaussianRV[
  DenseMatrix[Double], (DenseMatrix[Double], DenseMatrix[Double]), MatrixNormal] {

  override val underlyingDist = MatrixNormal(m, u, v)
}