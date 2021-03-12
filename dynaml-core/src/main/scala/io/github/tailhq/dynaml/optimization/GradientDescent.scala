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
package io.github.tailhq.dynaml.optimization

import breeze.linalg.DenseVector
import io.github.tailhq.dynaml.pipes.DataPipe
import org.apache.log4j.Logger
import spire.implicits._

/**
 * Implements Gradient Descent on the graph
 * generated to calculate approximate optimal
 * values of the model parameters.
 */
class GradientDescent (private var gradient: Gradient, private var updater: Updater)
  extends RegularizedOptimizer[DenseVector[Double],
    DenseVector[Double], Double, Stream[(DenseVector[Double], Double)]]{

  /**
   * Set the gradient function (of the loss function of one single data example)
   * to be used for SGD.
   */
  def setGradient(gradient: Gradient): this.type = {
    this.gradient = gradient
    this
  }


  /**
   * Set the updater function to actually perform a gradient step in a given direction.
   * The updater is responsible to perform the update from the regularization term as well,
   * and therefore determines what kind or regularization is used, if any.
   */
  def setUpdater(updater: Updater): this.type = {
    this.updater = updater
    this
  }

  /**
   * Find the optimum value of the parameters using
   * Gradient Descent.
   *
   * @param nPoints The number of data points
   * @param initialP The initial value of the parameters
   *                 as a [[DenseVector]]
   * @param ParamOutEdges An [[java.lang.Iterable]] object
   *                      having all of the out edges of the
   *                      parameter node
   *
   * @return The value of the parameters as a [[DenseVector]]
   *
   *
   * */
  override def optimize(
    nPoints: Long,
    ParamOutEdges: Stream[(DenseVector[Double], Double)],
    initialP: DenseVector[Double])
  : DenseVector[Double] =
    if(this.miniBatchFraction == 1.0) {
      GradientDescent.runSGD(
        nPoints,
        this.regParam,
        this.numIterations,
        this.updater,
        this.gradient,
        this.stepSize,
        initialP,
        ParamOutEdges,
        DataPipe(identity[Stream[(DenseVector[Double], Double)]] _),
        logging,
        logging_rate
      )
    } else {
      GradientDescent.runBatchSGD(
        nPoints,
        this.regParam,
        this.numIterations,
        this.updater,
        this.gradient,
        this.stepSize,
        initialP,
        ParamOutEdges,
        this.miniBatchFraction,
        DataPipe(identity[Stream[(DenseVector[Double], Double)]] _),
        logging,
        logging_rate
      )
    }

}

object GradientDescent {

  private val logger = Logger.getLogger(this.getClass)

  def runSGD[T](
      nPoints: Long,
      regParam: Double,
      numIterations: Int,
      updater: Updater,
      gradient: Gradient,
      stepSize: Double,
      initial: DenseVector[Double],
      POutEdges: T,
      transform: DataPipe[T, Stream[(DenseVector[Double], Double)]],
      logging: Boolean = true,
      logging_rate: Int = 100): DenseVector[Double] = {

    var oldW: DenseVector[Double] = initial
    var newW = oldW

    println("Performing SGD")
    cfor(1)(count => count < numIterations, count => count + 1)( count => {

      val cumGradient: DenseVector[Double] = DenseVector.zeros(initial.length)
      var cumLoss: Double = 0.0
      transform.run(POutEdges).foreach((ed) => {
        val x = DenseVector(ed._1.toArray ++ Array(1.0))
        val y = ed._2
        cumLoss += gradient.compute(x, y, oldW, cumGradient)
      })

      if(logging && count % logging_rate == 0) RegularizedOptimizer.prettyPrint(count, cumLoss/nPoints.toDouble)

      newW = updater.compute(oldW, cumGradient / nPoints.toDouble,
        stepSize, count, regParam)._1
      oldW = newW
    })

    newW
  }

  def runBatchSGD[T](
      nPoints: Long,
      regParam: Double,
      numIterations: Int,
      updater: Updater,
      gradient: Gradient,
      stepSize: Double,
      initial: DenseVector[Double],
      POutEdges: T,
      miniBatchFraction: Double,
      transform: DataPipe[T, Stream[(DenseVector[Double], Double)]],
      logging: Boolean = true,
      logging_rate: Int = 100): DenseVector[Double] = {

    var oldW: DenseVector[Double] = initial
    var newW = oldW
    println("Performing batch SGD")

    cfor(1)(count => count < numIterations, count => count + 1)( count => {
      val cumGradient: DenseVector[Double] = DenseVector.zeros(initial.length)
      var cumLoss: Double = 0.0
      transform.run(POutEdges).foreach((ed) => {
        if(scala.util.Random.nextDouble() <= miniBatchFraction) {
          val x = DenseVector(ed._1.toArray ++ Array(1.0))
          val y = ed._2
          cumLoss += gradient.compute(x, y, oldW, cumGradient)
        }
      })

      if(logging && count % logging_rate == 0) RegularizedOptimizer.prettyPrint(count, cumLoss/nPoints.toDouble)

      newW = updater.compute(oldW, cumGradient / nPoints.toDouble,
        stepSize, count, regParam)._1
      oldW = newW
    })
    newW
  }

}