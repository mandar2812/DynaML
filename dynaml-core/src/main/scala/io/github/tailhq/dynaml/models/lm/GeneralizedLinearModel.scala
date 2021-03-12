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

package io.github.tailhq.dynaml.models.lm

import breeze.linalg.DenseVector
import io.github.tailhq.dynaml.evaluation.Metrics
import io.github.tailhq.dynaml.models.LinearModel
import io.github.tailhq.dynaml.optimization.GloballyOptimizable

import scala.util.Random

/**
  * @author tailhq date: 4/4/16.
  *
  * A generalised linear model for local data sets.
  * This is extended for Regression in [[RegularizedGLM]]
  * and for binary classification in [[LogisticGLM]] and [[ProbitGLM]]
  */
abstract class GeneralizedLinearModel[T](
  data: Stream[(DenseVector[Double], Double)], numPoints: Int,
  map: (DenseVector[Double]) => DenseVector[Double] = identity[DenseVector[Double]])
  extends GenericGLM[Stream[(DenseVector[Double], Double)], T](data, numPoints, map)
    with GloballyOptimizable {

  override protected val g: Stream[(DenseVector[Double], Double)] = data

  val task: String

  override val h: (Double) => Double = identity

  featureMap = map

  def dimensions = featureMap(data.head._1).length

  /**
    * Initialize parameters to a vector of ones.
    * */
  override def initParams(): DenseVector[Double] =
    DenseVector.ones[Double](dimensions + 1)


  override protected var params: DenseVector[Double] = initParams()

  override protected var hyper_parameters: List[String] =
    List("regularization")

  /**
    * Calculates the energy of the configuration,
    * in most global optimization algorithms
    * we aim to find an approximate value of
    * the hyper-parameters such that this function
    * is minimized.
    *
    * @param h       The value of the hyper-parameters in the configuration space
    * @param options Optional parameters about configuration
    * @return Configuration Energy E(h)
    **/
  override def energy(h: Map[String, Double],
                      options: Map[String, String]): Double = {

    setState(h)
    val folds: Int = options("folds").toInt
    val shuffle = Random.shuffle((1L to numPoints).toList)

    val avg_metrics: DenseVector[Double] = (1 to folds).map { a =>
      //For the ath fold
      //partition the data
      //ceil(a-1*npoints/folds) -- ceil(a*npoints/folds)
      //as test and the rest as training
      val test = shuffle.slice((a - 1) * numPoints / folds, a * numPoints / folds)
      val (trainingData, testData) = g.zipWithIndex.partition((c) => !test.contains(c._2))
      val tempParams = optimizer.optimize(numPoints,
        prepareData(trainingData.map(_._1)),
        initParams())

      val scoresAndLabels = testData.map(_._1).map(p =>
        (this.h(tempParams dot DenseVector(featureMap(p._1).toArray ++ Array(1.0))), p._2))

      val metrics = Metrics("classification")(
        scoresAndLabels.toList,
        testData.length,
        logFlag = true)
      val res: DenseVector[Double] = metrics.kpi() / folds.toDouble
      res
    }.reduce(_+_)
    //Perform n-fold cross validation

    task match {
      case "regression" => avg_metrics(1)
      case "classification" => 1 - avg_metrics(2)
    }
  }

  override protected var current_state: Map[String, Double] = Map("regularization" -> 0.001)

  /**
    * Set the model "state" which
    * contains values of its hyper-parameters
    * with respect to the covariance and noise
    * kernels.
    * */
  def setState(s: Map[String, Double]): this.type ={
    this.setRegParam(s("regularization"))
    current_state = Map("regularization" -> s("regularization"))
    this
  }
}




object GeneralizedLinearModel {

  /**
    *  Create a generalized linear model.
    *
    *  @param data The training data as a stream of tuples
    *  @param task Set to 'regression' or 'classification'
    *  @param map Feature map or basis functions
    *  @param modeltype Set to either 'logit' or 'probit'
    *
    * */
  def apply[T](data: Stream[(DenseVector[Double], Double)],
               task: String = "regression",
               map: (DenseVector[Double]) => DenseVector[Double] =
               identity[DenseVector[Double]],
               modeltype: String = "") = task match {
    case "regression" => new RegularizedGLM(data, data.length, map).asInstanceOf[GeneralizedLinearModel[T]]
    case "classification" => modeltype match {
      case "probit" => new ProbitGLM(data, data.length, map).asInstanceOf[GeneralizedLinearModel[T]]
      case _ => new LogisticGLM(data, data.length, map).asInstanceOf[GeneralizedLinearModel[T]]
    }
  }
}
