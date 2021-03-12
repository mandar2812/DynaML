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
package io.github.tailhq.dynaml.pipes

trait DataPipeConvertible[-Source, Destination] {
  def toPipe: Source => Destination
}

case class ComposedPipe[-Source, Intermediate, Destination](
  val first: DataPipeConvertible[Source, Intermediate],
  val second: DataPipeConvertible[Intermediate, Destination]) extends 
  DataPipe[Source, Destination] {

    override def run(data: Source): Destination = second.toPipe(first.toPipe(data))
  }

/**
  * Top level trait representing an
  * abstract pipe that defines a transformation
  * between two data types, i.e. [[Source]] and [[Destination]]
  * @author tailhq on 18/11/15.
  *
  * */
trait DataPipe[-Source, Destination] extends DataPipeConvertible[Source, Destination] with Serializable {

  self =>

  def run(data: Source): Destination

  def apply(data: Source): Destination = run(data)

  def apply[T <: Traversable[Source]](data: T): T =
    data.map(run).asInstanceOf[T]

  /**
    * Represents the composition of two
    * pipes, resulting in a third pipe
    * Schematically represented as:
    *
    * [[Source]] -> [[Destination]] :: [[Destination]] -> [[Further]] ==
    * [[Source]] -> [[Further]]
    *
    * */
  def >[Further](other: DataPipeConvertible[Destination, Further]) =
    ComposedPipe(self, other)
    //DataPipe((d: Source) => that.toPipe(self.run(d)))

  /*def >[Result1, Result2](that: BifurcationPipe[Destination, Result1, Result2])
  : BifurcationPipe[Source, Result1, Result2] = DataPipe((x: Source) => that.run(self.run(x)))*/

  /**
    * Represents the composition of two
    * pipes, one a vanilla data pipe and
    * the other a basis expansion,
    * resulting in a third pipe
    * Schematically represented as:
    *
    * [[Source]] -> [[Destination]] :: [[Destination]] -> [[breeze.linalg.DenseVector]] ==
    * [[Source]] -> [[breeze.linalg.DenseVector]]
    *
    * */
  def %>(that: Basis[Destination]): Basis[Source] = Basis((d: Source) => that.run(self.run(d)))

  def *[OtherSource, OtherDestination](that: DataPipe[OtherSource, OtherDestination])
  :ParallelPipe[Source, Destination, OtherSource, OtherDestination] = ParallelPipe(self, that)

  def >-<[OtherSource, OtherDestination](that: DataPipe[OtherSource, OtherDestination])
  :DataPipe2[Source, OtherSource, (Destination, OtherDestination)] =
    DataPipe2((d1: Source, d2: OtherSource) => (self(d1), that(d2)))

  override def toPipe: Source => Destination = self.run _
}

object DataPipe {

  def apply[D](func: () => D): DataPipe[Unit, D] = new DataPipe[Unit, D] {
    def run(x: Unit) = func()
  }

  def apply[S, D](func: S => D):
  DataPipe[S, D] = {
    new DataPipe[S,D] {
      def run(data: S) = func(data)
    }
  }

  def apply[S1, D1, S2, D2](pipe1: DataPipe[S1, D1], pipe2: DataPipe[S2, D2]): ParallelPipe[S1, D1, S2, D2] =
    ParallelPipe(pipe1, pipe2)

  /* def apply[S, D1, D2](func: S => (D1, D2)):
  DataPipe[S, (D1, D2)] = {
    DataPipe[S, (D1, D2)](func)
  } */

  def apply[S](func: S => Unit): SideEffectPipe[S] = {
    new SideEffectPipe[S] {
      def run(data: S) = func(data)
    }
  }
}

case class ParallelPipe[-Source1, Result1, -Source2, Result2](
  val _1: DataPipe[Source1, Result1],
  val _2: DataPipe[Source2, Result2]
) extends DataPipe[(Source1, Source2), (Result1, Result2)] {

  override def run(data: (Source1, Source2)): (Result1, Result2) = (_1(data._1), _2(data._2))
}

case class BifurcationPipe[-Source, Result1, Result2](
  val pipe_like: DataPipeConvertible[Source, (Result1, Result2)]
) extends DataPipe[Source, (Result1, Result2)] {

  self =>

  override def run(data: Source): (Result1, Result2) = pipe_like.toPipe(data)

  def >[FinalResult](other: DataPipe2[Result1, Result2, FinalResult]): DataPipe[Source, FinalResult] =
    DataPipe((input: Source) => {
      val (x, y) = self.run(input)
      other.run(x, y)
    })

}

trait SideEffectPipe[I] extends DataPipe[I, Unit] {

}

object BifurcationPipe {

  def apply[Source, Destination1, Destination2](
    f: Source => (Destination1, Destination2))
    : BifurcationPipe[Source, Destination1, Destination2] = BifurcationPipe(DataPipe(f))

  def apply[Source, Destination1, Destination2](
    pipe1: DataPipe[Source, Destination1],
    pipe2: DataPipe[Source, Destination2]):
  BifurcationPipe[Source, Destination1, Destination2] = {
    BifurcationPipe(DataPipe((x: Source) => (pipe1.run(x), pipe2.run(x))))
  }
}

trait ReducerPipe[I] extends DataPipe[Array[I], I]

class Tuple2_1[I, J] extends DataPipe[(I, J), I] {
  override def run(data: (I, J)) = data._1
}

object Tuple2_1 {
  def apply[I, J] = new Tuple2_1[I, J]
}

class Tuple2_2[I, J] extends DataPipe[(I, J), J] {
  override def run(data: (I, J)) = data._2
}

object Tuple2_2 {
  def apply[I, J] = new Tuple2_2[I, J]
}