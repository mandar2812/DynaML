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
package io.github.tailhq.dynaml.probability.distributions

import breeze.linalg.{DenseMatrix, cholesky, diag, sum, trace}
import breeze.numerics.log
import breeze.stats.distributions.{ContinuousDistr, Moments, Rand, RandBasis}

import scala.math.log1p

/**
  * Matrix normal distribution over n &times; p matrices
  *
  * @param m The mode, mean and center of the distribution
  * @param u The n &times; n covariance matrix of the rows
  * @param v The p &times; p covariance matrix of the columns
  *
  * @author tailhq date: 05/02/2017.
  * */
case class MatrixNormal(
  m: DenseMatrix[Double],
  u: DenseMatrix[Double],
  v: DenseMatrix[Double])(
  implicit rand: RandBasis = Rand) extends
  AbstractContinuousDistr[DenseMatrix[Double]] with
  Moments[DenseMatrix[Double], (DenseMatrix[Double], DenseMatrix[Double])] with
  HasErrorBars[DenseMatrix[Double]] {

  private lazy val (rootu, rootv) = (cholesky(u), cholesky(v))

  private val (n,p) = (u.rows, v.cols)

  override def unnormalizedLogPdf(x: DenseMatrix[Double]) = {
    val d = x - m
    val y = rootu.t \ (rootu \ d)

    -0.5*trace(rootv.t\(rootv\(d.t*y)))
  }

  override lazy val logNormalizer = {
    val detU = sum(log(diag(rootu)))
    val detV = sum(log(diag(rootv)))

    0.5*(log(2.0*math.Pi)*n*p + detU*p + detV*n)
  }

  override def mean = m

  override def variance = (u,v)

  override def mode = m

  override def draw() = {
    val z: DenseMatrix[Double] = DenseMatrix.rand(m.rows, m.cols, rand.gaussian(0.0, 1.0))
    mean + (rootu*z*rootv.t)
  }

  lazy val entropy = {
    m.rows * m.cols * (log1p(2 * math.Pi) + sum(log(diag(rootu))) + sum(log(diag(rootv))))
  }

  override def confidenceInterval(s: Double) = {
    val signFlag = if(s < 0) -1.0 else 1.0

    val ones = DenseMatrix.ones[Double](mean.rows, mean.cols)
    val multiplier = signFlag*s

    val z = ones*multiplier

    val bar: DenseMatrix[Double] = rootu*z*rootv.t

    (mean - bar, mean + bar)
  }
}
