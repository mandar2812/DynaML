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
package io.github.tailhq.dynaml.examples

import breeze.linalg.DenseVector
import io.github.tailhq.dynaml.DynaMLPipe
import io.github.tailhq.dynaml.evaluation.RegressionMetrics
import io.github.tailhq.dynaml.graph.FFNeuralGraph
import io.github.tailhq.dynaml.models.neuralnets.FeedForwardNetwork
import io.github.tailhq.dynaml.pipes.DataPipe

/**
  * Created by mandar on 15/12/15.
  */
object TestNNHousing {

  def apply(hidden: Int = 2, nCounts:List[Int] = List(), acts:List[String], trainFraction: Double = 0.75,
            columns: List[Int] = List(13,0,1,2,3,4,5,6,7,8,9,10,11,12),
            stepSize: Double = 0.01, maxIt: Int = 300, mini: Double = 1.0,
            alpha: Double = 0.0, regularization: Double = 0.5): Unit =
    runExperiment(hidden, nCounts, acts,
      (506*trainFraction).toInt, columns,
      Map("tolerance" -> "0.0001",
        "step" -> stepSize.toString,
        "maxIterations" -> maxIt.toString,
        "miniBatchFraction" -> mini.toString,
        "momentum" -> alpha.toString,
        "regularization" -> regularization.toString
      )
    )

  def runExperiment(hidden: Int = 2, nCounts:List[Int] = List(), act: List[String],
                    num_training: Int = 200, columns: List[Int] = List(40,16,21,23,24,22,25),
                    opt: Map[String, String]): Unit = {

    val modelTrainTest =
      (trainTest: ((Iterable[(DenseVector[Double], Double)],
      Iterable[(DenseVector[Double], Double)]),
        (DenseVector[Double], DenseVector[Double]))) => {

        val gr = FFNeuralGraph(trainTest._1._1.head._1.length, 1, hidden,
          act, nCounts)

        val transform = DataPipe((d: Stream[(DenseVector[Double], Double)]) =>
          d.map(el => (el._1, DenseVector(el._2))))

        val model = new FeedForwardNetwork[Stream[(DenseVector[Double], Double)]](trainTest._1._1.toStream, gr)(transform)

        model.setLearningRate(opt("step").toDouble)
          .setMaxIterations(opt("maxIterations").toInt)
          .setBatchFraction(opt("miniBatchFraction").toDouble)
          .setMomentum(opt("momentum").toDouble)
          .setRegParam(opt("regularization").toDouble)
          .learn()

        val res = model.test(trainTest._1._2.toStream)
        val scoresAndLabelsPipe =
          DataPipe(
            (res: Seq[(DenseVector[Double], DenseVector[Double])]) =>
              res.map(i => (i._1(0), i._2(0))).toList) > DataPipe((list: List[(Double, Double)]) =>
            list.map{l => (l._1*trainTest._2._2(-1) + trainTest._2._1(-1),
              l._2*trainTest._2._2(-1) + trainTest._2._1(-1))})

        val scoresAndLabels = scoresAndLabelsPipe.run(res)

        val metrics = new RegressionMetrics(scoresAndLabels,
          scoresAndLabels.length)

        metrics.print()
        metrics.generatePlots()
      }

    //Load Housing data into a stream
    //Extract the time and Dst values
    //separate data into training and test
    //pipe training data to model and then generate test predictions
    //create RegressionMetrics instance and produce plots

    val preProcessPipe = DynaMLPipe.fileToStream >
      DynaMLPipe.trimLines >
      DynaMLPipe.replaceWhiteSpaces >
      DynaMLPipe.extractTrainingFeatures(columns, Map()) >
      DynaMLPipe.splitFeaturesAndTargets

    val trainTestPipe = DynaMLPipe.duplicate(preProcessPipe) >
      DynaMLPipe.splitTrainingTest(num_training, 506-num_training) >
      DynaMLPipe.trainTestGaussianStandardization >
      DataPipe(modelTrainTest)

    val dataFile = dataDir+"/housing.data"
    trainTestPipe.run((dataFile, dataFile))

  }

}
