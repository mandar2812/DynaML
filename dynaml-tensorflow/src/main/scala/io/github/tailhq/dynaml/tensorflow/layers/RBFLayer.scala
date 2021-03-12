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
package io.github.tailhq.dynaml.tensorflow.layers

import org.platanios.tensorflow.api.core.types.{IsDecimal, IsNotQuantized, TF}
import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.learn.layers.Layer
import org.platanios.tensorflow.api.ops.variables.Initializer
import org.platanios.tensorflow.api._

/**
  * Radial Basis Function Feedforward Layer.
  *
  * @param name Name of the layer.
  * @param num_units The number of centers/units.
  * @param rbf The radial basis function to use, defaults to gaussian radial basis.
  * @param centers_initializer The initialization of the node centers.
  * @param scales_initializer The initialization of the node length scales.
  * @param weights_initializer The initialization of the node importance weights.
  * @author tailhq date 30/03/2018
  * */
case class RBFLayer[T: TF : IsDecimal : IsNotQuantized](
  override val name: String,
  num_units: Int,
  rbf: RBFLayer.RadialBasisFunction = RBFLayer.Gaussian,
  centers_initializer: Initializer = tf.RandomNormalInitializer(),
  scales_initializer: Initializer  = tf.OnesInitializer,
  weights_initializer: Initializer = tf.RandomNormalInitializer()) extends
    Layer[Output[T], Output[T]](name) {

  override val layerType: String = s"RBFLayer[num_units:$num_units]"

  override def forwardWithoutContext(input: Output[T])(implicit mode: Mode): Output[T] = {

    val node_centers    = (0 until num_units).map(
      i => tf.variable[T]("node_"+i, Shape(input.shape(-1)), centers_initializer)
    )

    val scales          = (0 until num_units).map(
      i => tf.variable[T]("scale_"+i, Shape(input.shape(-1)), scales_initializer)
    )

    tf.concatenate(
      node_centers.zip(scales).map(cs => rbf(input, cs._1, cs._2.square)),
      axis = -1).reshape(Shape(-1, num_units).toOutput)
  }
}

object RBFLayer {

  sealed trait RadialBasisFunction {
    def apply[T: TF : IsDecimal : IsNotQuantized](input: Output[T], center: Output[T], scale: Output[T]): Output[T]
  }

  object Gaussian extends RadialBasisFunction {
    override def apply[T: TF : IsDecimal : IsNotQuantized](
      input: Output[T],
      center: Output[T],
      scale: Output[T]): Output[T] =
      input.subtract(center)
        .square.multiply(scale)
        .multiply(Tensor(-1.0).toOutput.castTo[T])
        .sum(axes = 1).exp
  }

  object MultiQuadric extends RadialBasisFunction {
    override def apply[T: TF : IsDecimal : IsNotQuantized](
      input: Output[T], center: Output[T], scale: Output[T]): Output[T] =
      input.subtract(center)
        .square
        .multiply(scale)
        .sum(axes = 1).add(Tensor(1.0).toOutput.castTo[T])
        .sqrt
  }

  object InverseMultiQuadric extends RadialBasisFunction {
    override def apply[T: TF : IsDecimal : IsNotQuantized](
      input: Output[T],
      center: Output[T],
      scale: Output[T]): Output[T] =
      input.subtract(center)
        .square
        .multiply(scale)
        .sum(axes = 1)
        .add(Tensor(1.0).toOutput.castTo[T]).sqrt.pow(Tensor(-1.0).toOutput.castTo[T])
  }

  object InverseQuadric extends RadialBasisFunction {
    override def apply[T: TF : IsDecimal : IsNotQuantized](
      input: Output[T],
      center: Output[T],
      scale: Output[T]): Output[T] =
      input.subtract(center)
        .square.multiply(scale).sum(axes = 1)
        .add(Tensor(1.0).toOutput.castTo[T])
        .pow(Tensor(-1.0).toOutput.castTo[T])
  }

  object Sigmoid extends RadialBasisFunction {
    override def apply[T: TF : IsDecimal : IsNotQuantized](
      input: Output[T],
      center: Output[T],
      scale: Output[T]): Output[T] =
      input.subtract(center)
        .square
        .multiply(scale)
        .sigmoid
  }

}
