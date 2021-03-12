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

import breeze.linalg.{DenseMatrix, cholesky, det, diag, sum}
import breeze.numerics._
import breeze.stats.distributions._
import io.github.tailhq.dynaml.utils.mvlgamma
import org.apache.spark.annotation.Experimental

/**
  * Matrix Students T distribution over n &times; p matrices
  *
  * @param mu Degrees of freedom
  * @param m The mode, mean and center of the distribution
  * @param omega The p &times; p covariance matrix of the columns
  * @param sigma The n &times; n covariance matrix of the rows
  * @author tailhq date: 05/02/2017.
  *
  * */
@Experimental
case class MatrixT(
  mu: Double,
  m: DenseMatrix[Double],
  omega: DenseMatrix[Double],
  sigma: DenseMatrix[Double])(
  implicit rand: RandBasis = Rand) extends
  AbstractContinuousDistr[DenseMatrix[Double]] with
  Moments[DenseMatrix[Double], (DenseMatrix[Double], DenseMatrix[Double])] with
  HasErrorBars[DenseMatrix[Double]] {

  assert(mu > 2.0, "Parameter mu in Matrix Students T must be greater than 2.0")

  private val chisq = new ChiSquared(mu)

  private lazy val (rootOmega, rootSigma) = (cholesky(omega), cholesky(sigma))

  private val (n,p) = (sigma.rows, omega.cols)

  private val i = DenseMatrix.eye[Double](n)

  override def unnormalizedLogPdf(x: DenseMatrix[Double]) = {
    val d = x - m
    val y = rootOmega.t \ (rootOmega \ d.t)

    -0.5*(mu+n+p-1)*det(i + rootSigma.t\(rootSigma\(d*y)))
  }

  override lazy val logNormalizer = {
    val detOmega = sum(log(diag(rootOmega)))
    val detSigma = sum(log(diag(rootSigma)))

    log(math.Pi)*n*p + detOmega*n + detSigma*p + mvlgamma(p, 0.5*(mu+p-1)) - mvlgamma(p, 0.5*(mu+n+p-1))
  }

  override def mean = m

  override def variance = (omega,sigma)

  override def mode = m

  override def draw() = {
    val w = math.sqrt(mu/chisq.draw())
    val z: DenseMatrix[Double] = DenseMatrix.rand(m.rows, m.cols, rand.gaussian(0.0, 1.0))
    mean + (rootSigma*z*rootOmega.t)*w
  }

  //TODO: Check and correct calculation of entropy for Matrix Students T
  @Experimental
  lazy val entropy = {
    sum(log(diag(rootOmega))) + sum(log(diag(rootSigma))) + (n*p/2.0)*log(mu*math.Pi) +
      lbeta(n*p/2.0, mu/2.0) - lgamma(n*p/2.0) +
      (digamma((mu+n*p)/2.0) - digamma(mu/2.0))*(mu+n*p)/2.0
  }

  override def confidenceInterval(s: Double) = {

    val signFlag = if(s < 0) -1.0 else 1.0

    val ones = DenseMatrix.ones[Double](mean.rows, mean.cols)
    val multiplier = signFlag*s*math.sqrt(mu/(mu-2.0))

    val z = ones*multiplier

    val bar: DenseMatrix[Double] = rootSigma*z*rootOmega.t

    (mean - bar, mean + bar)

  }
}
