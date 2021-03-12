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
package io.github.tailhq.dynaml.models.bayes

import breeze.linalg.DenseMatrix
import spire.algebra.{Field, InnerProductSpace}
import io.github.tailhq.dynaml.DynaMLPipe._
import io.github.tailhq.dynaml.algebra.PartitionedVector
import io.github.tailhq.dynaml.analysis.PartitionedVectorField
import io.github.tailhq.dynaml.kernels.{LocalScalarKernel, ScaledKernel}
import io.github.tailhq.dynaml.modelpipe.GPRegressionPipe2
import io.github.tailhq.dynaml.models.gp.AbstractGPRegressionModel

import scala.reflect.ClassTag
import io.github.tailhq.dynaml.pipes.{DataPipe, Encoder, MetaPipe, ParallelPipe}
import io.github.tailhq.dynaml.probability.{MatrixNormalRV, MultGaussianPRV}

/**
  * Represents a Gaussian Process Prior over functions.
  *
  * @tparam I The index set or domain
  * @tparam MeanFuncParams The type of the parameters
  *                        expressing the trend/mean function
  * @param covariance A [[LocalScalarKernel]] over the index set [[I]],
  *                   represents the covariance or kernel function of the
  *                   gaussian process prior measure.
  * @param noiseCovariance A [[LocalScalarKernel]] instance representing
  *                        the measurement noise over realisations of the prior.
  * @author tailhq date: 21/02/2017.
  * */
abstract class GaussianProcessPrior[I: ClassTag, MeanFuncParams](
  val covariance: LocalScalarKernel[I],
  val noiseCovariance: LocalScalarKernel[I]) extends
  StochasticProcessPrior[
    I, Double, PartitionedVector,
    MultGaussianPRV, MultGaussianPRV,
    AbstractGPRegressionModel[Seq[(I, Double)], I]] {

  self =>

  type GPModel = AbstractGPRegressionModel[Seq[(I, Double)], I]

  def _meanFuncParams: MeanFuncParams

  def meanFuncParams_(p: MeanFuncParams): Unit

  val trendParamsEncoder: Encoder[MeanFuncParams, Map[String, Double]]

  protected val initial_covariance_state: Map[String, Double] = covariance.state ++ noiseCovariance.state

  val meanFunctionPipe: MetaPipe[MeanFuncParams, I, Double]

  private var globalOptConfig = Map(
    "globalOpt" -> "GS",
    "gridSize" -> "3",
    "gridStep" -> "0.2",
    "policy" -> "GS")

  /**
    * Append the global optimization configuration
    * */
  def globalOptConfig_(conf: Map[String, String]) = globalOptConfig ++= conf

  /**
    * Data pipe which takes as input training data and a trend model,
    * outputs a tuned gaussian process regression model.
    * */
  def posteriorModelPipe =
    GPRegressionPipe2[I](covariance, noiseCovariance) >
    gpTuning(
      initial_covariance_state,
      globalOptConfig("globalOpt"),
      globalOptConfig("gridSize").toInt,
      globalOptConfig("gridStep").toDouble,
      maxIt = globalOptConfig.getOrElse("maxIt", "4").toInt,
      policy = globalOptConfig.getOrElse("policy", "GS"),
      prior = hyperPrior) >
    DataPipe((modelAndConf: (GPModel, Map[String, Double])) => modelAndConf._1)

  /**
    * Given some data, return a gaussian process regression model
    *
    * @param data A Sequence of input patterns and responses
    * */
  override def posteriorModel(data: Seq[(I, Double)]) =
    posteriorModelPipe(data, meanFunctionPipe(_meanFuncParams))

  /**
    * Returns the distribution of response values,
    * evaluated over a set of domain points of type [[I]].
    * */
  override def priorDistribution[U <: Seq[I]](d: U) = {

    val numPoints: Long = d.length.toLong

    //Declare vector field, required implicit parameter
    implicit val field: Field[PartitionedVector] =
      PartitionedVectorField(numPoints, covariance.rowBlocking)

    //Construct mean Vector
    val meanFunc = meanFunctionPipe(_meanFuncParams)
    val meanVector = PartitionedVector(
      d.toStream.map(meanFunc(_)),
      numPoints,
      covariance.rowBlocking)

    val effectiveCov = covariance + noiseCovariance
    //Construct covariance matrix
    val covMat = effectiveCov.buildBlockedKernelMatrix(d, numPoints)

    MultGaussianPRV(meanVector, covMat)
  }

  /**
    * Define a prior over the process which is a scaled version of the base GP.
    *
    * z ~ GP(m(.), K(.,.))
    *
    * y = g(x)&times;z
    *
    * y ~ GP(g(x)&times;m(x), g(x)K(x,x')g(x'))
    * */
  def *(scalingFunc: DataPipe[I, Double]): GaussianProcessPrior[I, MeanFuncParams] =
  GaussianProcessPrior[I, MeanFuncParams](
    ScaledKernel[I](self.covariance, scalingFunc),
    ScaledKernel[I](self.noiseCovariance, scalingFunc),
    MetaPipe((p: MeanFuncParams) => (x: I) => self.meanFunctionPipe(p)(x)*scalingFunc(x)),
    self.trendParamsEncoder,
    self._meanFuncParams)

}

object GaussianProcessPrior {

  /**
    * Create GP prior models on the fly
    * */
  def apply[I: ClassTag, MeanFuncParams](
    covariance: LocalScalarKernel[I],
    noiseCovariance: LocalScalarKernel[I],
    meanFPipe: MetaPipe[MeanFuncParams, I, Double],
    trendEncoder: Encoder[MeanFuncParams, Map[String, Double]],
    initialParams: MeanFuncParams): GaussianProcessPrior[I, MeanFuncParams] =
    new GaussianProcessPrior[I, MeanFuncParams](covariance, noiseCovariance) {

      private var params = initialParams

      override def _meanFuncParams = params

      override def meanFuncParams_(p: MeanFuncParams) = params = p

      override val meanFunctionPipe = meanFPipe

      override val trendParamsEncoder = trendEncoder
    }

}


/**
  * A gaussian process prior with a linear trend mean function.
  *
  * @author tailhq date 21/02/2017.
  * */
class LinearTrendGaussianPrior[I: ClassTag](
  cov: LocalScalarKernel[I], n: LocalScalarKernel[I],
  override val trendParamsEncoder: Encoder[(I, Double), Map[String, Double]],
  trendParams: I, intercept: Double)(
  implicit inner: InnerProductSpace[I, Double]) extends
  GaussianProcessPrior[I, (I, Double)](cov, n) with
  LinearTrendStochasticPrior[I, MultGaussianPRV, MultGaussianPRV, AbstractGPRegressionModel[Seq[(I, Double)], I]]{

  override val innerProduct = inner

  override protected var params: (I, Double) = (trendParams, intercept)

  override def _meanFuncParams = params

  override def meanFuncParams_(p: (I, Double)) = params = p

  override val meanFunctionPipe = MetaPipe(
    (parameters: (I, Double)) => (x: I) => inner.dot(parameters._1, x) + parameters._2
  )

}

/**
  *
  * Gaussian Process prior where the covariance can be
  * decomposed as a product of covariance functions over
  * two index sets [[I]] and [[J]].
  *
  * @tparam I Index set of the first component covariance
  * @tparam J Index set of the second component covariance
  * @tparam MeanFuncParams Type of the parameterization of the trend/mean function
  *
  * @param covarianceI First component covariance function
  * @param covarianceJ Second component covariance function
  * @param noiseCovarianceI First component of measurement noise
  * @param noiseCovarianceJ Second component of measurement noise
  * @author tailhq date: 2017/05/04
  *
  * */
abstract class CoRegGPPrior[I: ClassTag, J: ClassTag, MeanFuncParams](
  covarianceI: LocalScalarKernel[I], covarianceJ: LocalScalarKernel[J],
  noiseCovarianceI: LocalScalarKernel[I], noiseCovarianceJ: LocalScalarKernel[J],
  override val trendParamsEncoder: Encoder[MeanFuncParams, Map[String, Double]]) extends
  GaussianProcessPrior[(I,J), MeanFuncParams](
    covarianceI:*covarianceJ,
    noiseCovarianceI:*noiseCovarianceJ) {

  self =>

  def priorDistribution[U <: Seq[I], V <: Seq[J]](d1: U, d2: V): MatrixNormalRV = {

    val (rows, cols) = (d1.length, d2.length)
    val u = covarianceI.buildKernelMatrix(d1, rows).getKernelMatrix()
    val v = covarianceJ.buildKernelMatrix(d2, cols).getKernelMatrix()

    val m = DenseMatrix.tabulate[Double](rows, cols)((i,j) => meanFunctionPipe(_meanFuncParams)(d1(i), d2(j)))
    MatrixNormalRV(m, u, v)
  }

  /**
    * Define a prior over the process which is a scaled version of the base GP.
    *
    * z ~ GP(m(.), K(.,.))
    *
    * y = g(x)&times;z
    *
    * y ~ GP(g(x)&times;m(x), g(x)K(x,x')g(x'))
    **/
  def *(scalingFunc: ParallelPipe[I, Double, J, Double]) =
    CoRegGPPrior(
      ScaledKernel[I](self.covarianceI, scalingFunc._1),
      ScaledKernel[J](self.covarianceJ, scalingFunc._2),
      ScaledKernel[I](self.noiseCovarianceI, scalingFunc._1),
      ScaledKernel[J](self.noiseCovarianceJ, scalingFunc._2))(
      MetaPipe((p: MeanFuncParams) => (x: (I, J)) => {
        self.meanFunctionPipe(p)(x)*scalingFunc._1(x._1)*scalingFunc._2(x._2)
      }),
      self.trendParamsEncoder,
      self._meanFuncParams
    )
}

object CoRegGPPrior {

  /**
    * @tparam I Index set of the first component covariance
    * @tparam J Index set of the second component covariance
    * @tparam MeanFuncParams Type of the parameterization of the trend/mean function
    *
    * @param covarianceI First component covariance function
    * @param covarianceJ Second component covariance function
    * @param noiseCovarianceI First component of measurement noise
    * @param noiseCovarianceJ Second component of measurement noise
    * @param meanFPipe A [[MetaPipe]] which takes a the mean function parameters and
    *                  returns a [[DataPipe]] which is the mean function.
    * @param initialParams Initial assignment to the mean function parameters.
    *
    * @return A [[CoRegGPPrior]] with the specified trend function.
    * */
  def apply[I: ClassTag, J: ClassTag, MeanFuncParams](
    covarianceI: LocalScalarKernel[I], covarianceJ: LocalScalarKernel[J],
    noiseCovarianceI: LocalScalarKernel[I], noiseCovarianceJ: LocalScalarKernel[J])(
    meanFPipe: MetaPipe[MeanFuncParams, (I, J), Double],
    trendParamsEncoder: Encoder[MeanFuncParams, Map[String, Double]],
    initialParams: MeanFuncParams) =
    new CoRegGPPrior[I, J, MeanFuncParams](
      covarianceI, covarianceJ,
      noiseCovarianceI, noiseCovarianceJ,
      trendParamsEncoder) {

      private var params = initialParams

      override def _meanFuncParams = params

      override def meanFuncParams_(p: MeanFuncParams) = params = p

      override val meanFunctionPipe = meanFPipe
    }
}
