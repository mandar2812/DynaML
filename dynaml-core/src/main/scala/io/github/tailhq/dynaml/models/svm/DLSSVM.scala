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
package io.github.tailhq.dynaml.models.svm

import breeze.linalg.{DenseMatrix, DenseVector, inv, trace}
import io.github.tailhq.dynaml.kernels.{CovarianceFunction, LocalScalarKernel}
import io.github.tailhq.dynaml.models.LinearModel
import io.github.tailhq.dynaml.models.gp.AbstractGPRegressionModel
import io.github.tailhq.dynaml.optimization.{GloballyOptWithGrad, GloballyOptimizable, LSSVMLinearSolver, RegularizedOptimizer}

/**
  * Implementation of the classical Dual LSSVM model.
  *
  * @param data The underlying training data
  *
  * @param kern The kernel used to model the covariance
  *             structure of the outputs with respect to the
  *             input data.
  *
  * @param numPoints The number of data points in [[data]]
  *
  * @param modelTask Set to 'regression' or 'classification'
  */
class DLSSVM(data: Stream[(DenseVector[Double], Double)], numPoints: Int,
             kern: LocalScalarKernel[DenseVector[Double]],
             modelTask: String = "regression")
  extends AbstractDualLSSVM[DenseVector[Double]](data, numPoints, kern)
  with GloballyOptWithGrad {

  var task: String = modelTask

  /**
    * Model optimizer set to
    * [[LSSVMLinearSolver]] which
    * solves the LSSVM optimization
    * problem in the dual.
    * */
  override protected val optimizer = new LSSVMLinearSolver(modelTask)

  /**
    * Learn the parameters
    * of the model which
    * are in a node of the
    * graph.
    *
    **/
  override def learn(): Unit = {

    val kernelMat = kernel.buildKernelMatrix(g.map(_._1), num_points)
      .getKernelMatrix()

    //Create a vector of target values y
    val targets = DenseVector(g.map(_._2).toArray)

    params = optimizer.optimize(
      num_points,
      (kernelMat, targets),
      initParams())
  }


  /**
    * Predict the value of the
    * target variable given a
    * point.
    *
    **/
  override def predict(point: DenseVector[Double]): Double = task match {
    case "regression" => super.predict(point)
    case "classification" =>
      val features = DenseVector(g.map(inducingpoint =>
        kernel.evaluate(point, inducingpoint._1)).toArray)

      params(0 until num_points) dot features + params(-1)
  }

  /**
    * Calculates the energy of the configuration,
    * in most global optimization algorithms
    * we aim to find an approximate value of
    * the hyper-parameters such that this function
    * is minimized.
    *
    * @param h The value of the hyper-parameters in the configuration space
    * @param options Optional parameters about configuration
    * @return Configuration Energy E(h)
    **/
  override def energy(h: Map[String, Double], options: Map[String, String]): Double = {
    kernel.setHyperParameters(h)
    val kernelTraining = kernel.buildKernelMatrix(g.map(_._1), num_points).getKernelMatrix()
    val trainingLabels = DenseVector(g.map(_._2).toArray)
    val noiseMat = DenseMatrix.eye[Double](num_points)*h("regularization")

    AbstractGPRegressionModel.logLikelihood(trainingLabels, kernelTraining + noiseMat)
  }

  /**
    * Calculates the gradient energy of the configuration and
    * subtracts this from the current value of h to yield a new
    * hyper-parameter configuration.
    *
    * Over ride this function if you aim to implement a gradient based
    * hyper-parameter optimization routine like ML-II
    *
    * @param h The value of the hyper-parameters in the configuration space
    * @return Gradient of the objective function (marginal likelihood) as a Map
    **/
  override def gradEnergy(h: Map[String, Double]): Map[String, Double] = {
    kernel.setHyperParameters(h)
    val kernelTraining = kernel.buildKernelMatrix(g.map(_._1), num_points).getKernelMatrix()
    val trainingLabels = DenseVector(g.map(_._2).toArray)
    val noiseMat = DenseMatrix.eye[Double](num_points)*h("regularization")
    val training = g.map(_._1)
    val inverse = inv(kernelTraining + noiseMat)

    val alpha = inverse * trainingLabels


    hyper_parameters.map(h => {
        //build kernel derivative matrix

      val kernelDerivative = if(h == "regularization") {
        DenseMatrix.eye[Double](num_points)
      } else {
        DenseMatrix.tabulate[Double](num_points, num_points){(i,j) => {
          kernel.gradient(training(i), training(j))(h)
        }}
      }

      val grad: DenseMatrix[Double] = (alpha*alpha.t - inverse)*kernelDerivative
      (h, trace(grad))
    }).toMap
  }


}
