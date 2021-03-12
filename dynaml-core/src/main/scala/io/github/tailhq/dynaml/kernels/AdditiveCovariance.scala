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
package io.github.tailhq.dynaml.kernels

/**
  * A kernel formed by addition
  * of two kernels k(.,.) = k1(.,.) + k2(.,.)
  * @author mandar date: 22/01/2017.
  *
  * */
class AdditiveCovariance[Index](
  val firstKernel: LocalScalarKernel[Index],
  val otherKernel: LocalScalarKernel[Index]) extends
  CompositeCovariance[Index] {

  val (fID, sID) = (firstKernel.toString.split("\\.").last, otherKernel.toString.split("\\.").last)

  override val hyper_parameters =
    firstKernel.hyper_parameters.map(h => fID+"/"+h) ++
      otherKernel.hyper_parameters.map(h => sID+"/"+h)

  private def getKernelConfigs(config: Map[String, Double]) = (
    config.filter(_._1.contains(fID)).map(CompositeCovariance.truncateState),
    config.filter(_._1.contains(sID)).map(CompositeCovariance.truncateState)
  )

  protected def getKernelHyp(s: Seq[String]) = (
    s.filter(_.contains(fID)).map(CompositeCovariance.truncateHyp),
    s.filter(_.contains(sID)).map(CompositeCovariance.truncateHyp)
  )

  override def evaluateAt(config: Map[String, Double])(x: Index, y: Index) = {

    val (firstKernelConfig, secondKernelConfig) = getKernelConfigs(config)

    firstKernel.evaluateAt(firstKernelConfig)(x,y) + otherKernel.evaluateAt(secondKernelConfig)(x,y)
  }

  state = firstKernel.state.map(h => (fID+"/"+h._1, h._2)) ++ otherKernel.state.map(h => (sID+"/"+h._1, h._2))

  blocked_hyper_parameters =
    firstKernel.blocked_hyper_parameters.map(h => fID+"/"+h) ++
      otherKernel.blocked_hyper_parameters.map(h => sID+"/"+h)

  override def setHyperParameters(h: Map[String, Double]): this.type = {

    val (firstKernelConfig, secondKernelConfig) = getKernelConfigs(h)

    firstKernel.setHyperParameters(firstKernelConfig)
    otherKernel.setHyperParameters(secondKernelConfig)
    super.setHyperParameters(h)
  }

  override def block(h: String*) = {

    val (firstKernelHyp, secondKernelHyp) = getKernelHyp(h)
    firstKernel.block(firstKernelHyp:_*)
    otherKernel.block(secondKernelHyp:_*)
    super.block(h:_*)
  }

  override def gradientAt(config: Map[String, Double])(x: Index, y: Index): Map[String, Double] = {

    val (firstKernelConfig, secondKernelConfig) = getKernelConfigs(config)

    firstKernel.gradientAt(firstKernelConfig)(x, y).map(h => (fID+"/"+h._1, h._2)) ++
      otherKernel.gradientAt(secondKernelConfig)(x,y).map(h => (sID+"/"+h._1, h._2))

  }

  override def buildKernelMatrix[S <: Seq[Index]](mappedData: S, length: Int) =
    SVMKernel.buildSVMKernelMatrix[S, Index](mappedData, length, this.evaluate)

  override def buildCrossKernelMatrix[S <: Seq[Index]](dataset1: S, dataset2: S) =
    SVMKernel.crossKernelMatrix(dataset1, dataset2, this.evaluate)

}
