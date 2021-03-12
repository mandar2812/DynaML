/*
Copyright 2015 Mandar Chandorkar

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
package io.github.tailhq.dynaml.models.gp

import breeze.linalg.{DenseMatrix, DenseVector, cholesky, trace, inv}
import breeze.numerics.{log, sqrt}
import io.github.tailhq.dynaml.algebra._
import io.github.tailhq.dynaml.analysis._
import io.github.tailhq.dynaml.algebra.PartitionedMatrixOps._
import io.github.tailhq.dynaml.algebra.PartitionedMatrixSolvers._
import io.github.tailhq.dynaml.kernels._
import io.github.tailhq.dynaml.models.{ContinuousProcessModel, SecondOrderProcessModel}
import io.github.tailhq.dynaml.optimization.GloballyOptWithGrad
import io.github.tailhq.dynaml.pipes.{DataPipe, DataPipe2}
import io.github.tailhq.dynaml.probability.{MultGaussianPRV, MultGaussianRV}
import org.apache.log4j.Logger

import scala.reflect.ClassTag

/**
  * <h3>Basis Function Gaussian Process Regression</h3>
  *
  * Single-Output Gaussian Process Regression Model
  * Performs gp/spline smoothing/regression with
  * vector inputs and a singular scalar output.
  *
  * The model incorporates explicit basis functions which are
  * used to parameterize the mean/trend function.
  * 
  * @tparam T The data structure holding the training data.
  *
  * @tparam I The index set over which the Gaussian Process
  *           is defined.
  *           
  * @param cov The covariance function/kernel of the GP model,
  *            expressed as a [[LocalScalarKernel]] instance
  *
  * @param n Measurement noise covariance of the GP model.
  *
  * @param data Training data set of generic type [[T]]
  *
  * @param num The number of training data instances.
  *
  * @param basisFunc A basis function representation for the input features,
  *                  represented as a [[DataPipe]].
  *
  * @param basis_param_prior A Gaussian prior on the basis function trend coefficients.
  *
  * @author tailhq date 2017/08/09
  * */
abstract class GPBasisFuncRegressionModel[T, I: ClassTag](
  cov: LocalScalarKernel[I], n: LocalScalarKernel[I],
  data: T, num: Int, basisFunc: DataPipe[I, DenseVector[Double]],
  basis_param_prior: MultGaussianRV) extends AbstractGPRegressionModel[T, I](
  cov, n, data, num) {

  val MultGaussianRV(b, covB) = basis_param_prior

  implicit val vf = VectorField(b.length)

  private lazy val lowB = cholesky(covB)

  private lazy val covBsolveb = lowB.t \ (lowB \ b)

  private lazy val h: PartitionedMatrix = PartitionedMatrix.horzcat(_blockSize)(trainingData.map(basisFunc(_)):_*)

  override val mean: DataPipe[I, Double] = basisFunc > DataPipe((h: DenseVector[Double]) => h.t * b)

  private val basisFeatureMap: DataPipe[I, DenseVector[Double]] = basisFunc > DataPipe((x: DenseVector[Double]) => lowB*x)

  val feature_map_cov = CovarianceFunction(basisFunc > DataPipe((x: DenseVector[Double]) => lowB*x))

  override protected def getTrainKernelMatrix[U <: Seq[I]] = {
    SVMKernel.buildPartitionedKernelMatrix(trainingData,
      trainingData.length, _blockSize, _blockSize,
      (x: I, y: I) => {covariance.evaluate(x, y) + feature_map_cov.evaluate(x, y) + noiseModel.evaluate(x, y)}
    )
  }

  override protected def getCrossKernelMatrix[U <: Seq[I]](test: U) =
    SVMKernel.crossPartitonedKernelMatrix(
      trainingData, test, _blockSize, _blockSize,
      (x: I, y: I) => {covariance.evaluate(x, y) + feature_map_cov.evaluate(x, y)}
    )

  override protected def getTestKernelMatrix[U <: Seq[I]](test: U) =
    SVMKernel.buildPartitionedKernelMatrix(
      test, test.length.toLong,
      _blockSize, _blockSize,
      (x: I, y: I) => {covariance.evaluate(x, y) + feature_map_cov.evaluate(x, y)}
    )


}
