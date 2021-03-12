package io.github.tailhq.dynaml.tensorflow.dynamics

import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import _root_.io.github.tailhq.dynaml.utils
import _root_.io.github.tailhq.dynaml.analysis.implicits._
import _root_.io.github.tailhq.dynaml.tensorflow._
import _root_.io.github.tailhq.dynaml.tensorflow.pde._
import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.learn.layers.Layer
import org.platanios.tensorflow.api._
import scala.util.Random

class WavePDESpec extends FlatSpec with Matchers with BeforeAndAfter {

  protected val random = new Random()

  protected def batch(dim: Int, min: Double, max: Double, gridSize: Int): Tensor[Float] = {

    val points = utils.combine(Seq.fill(dim)(utils.range(min, max, gridSize)))


    dtf.tensor_f32(Seq.fill(dim)(gridSize).product, dim)(points.flatten.map(_.toFloat):_*)
  }

  protected val layer: Layer[Output[Float], Output[Float]] = new Layer[Output[Float], Output[Float]]("Exp") {
    override val layerType = "Exp"

    override def forwardWithoutContext(input: Output[Float])(implicit mode: Mode): Output[Float] =
      tf.sin(input)
  }


  protected implicit val mode: Mode = tf.learn.INFERENCE

  protected val domain = (0.0, 5.0)

  protected val domain_size = domain._2 - domain._1

  protected val input_dim: Int = 2

  protected val output_dim: Int = 1

  protected val inputs = tf.placeholder[Float](Shape(-1, input_dim))

  protected val feedforward = new Layer[Output[Float], Output[Float]]("Exp") {
    override val layerType = "MatMul"

    override def forwardWithoutContext(input: Output[Float])(implicit mode: Mode): Output[Float] =
      tf.reshape[Float, Int](
        tf.matmul(
          input,
          dtf.tensor_f32(input.shape(1), output_dim)(
            Seq.tabulate[Float](
              input.shape(1), output_dim)(
              (i, j) => if(i == j) math.Pi.toFloat*2/domain_size.toFloat else -math.Pi.toFloat*2/domain_size.toFloat
            ).flatten:_*
          ).toOutput
        ),
        Shape(input.shape(0)).toOutput
      )
  }

  protected val function  = feedforward >> layer

  protected val outputs   = function.forward(inputs)

  protected val df_dt     = d_t(d_t)(function)(inputs)

  protected val df_ds     = d_s(d_s)(function)(inputs)

  protected val velocity  = variable[Output[Float], Float](
    "wave_velocity",
    Shape(),
    tf.ConstantInitializer[Float](Tensor[Float](1.0f)))

  protected val wave_op   = d_t(d_t) - d_s(d_s)*velocity

  protected val op_f      = wave_op(function)(inputs)

  protected var session: Session = _

  protected var trainBatch: Tensor[Float] = _

  protected var feeds: Map[Output[Float], Tensor[Float]] = _

  protected var results: (Tensor[Float], Tensor[Float], Tensor[Float], Tensor[Float]) = _

  before {

    session = Session()

    session.run(targets = tf.globalVariablesInitializer())

    trainBatch = batch(dim = input_dim, 0.0, 5.0, gridSize = 10)

    feeds = Map(inputs -> trainBatch)

    results = session.run(feeds = feeds, fetches = (outputs, df_ds, df_dt, op_f))


  }



  after {
    session.close()
  }

  "Wave Equation " should "have 'wave_velocity' as the only epistemic source" in {

    assert(
      wave_op.variables.toSeq.length == 1 &&
        wave_op.variables.keys.toSeq == Seq("wave_velocity") &&
        wave_op.variables.toSeq.head._2.name == "wave_velocity")
  }

  "Plane wave solution" should " satisfy the wave PDE operator exactly" in {
    assert(results._2 == results._3 && results._4 == dtf.fill[Float](10*10)(0f))
  }


}
