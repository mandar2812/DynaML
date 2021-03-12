package io.github.tailhq.dynaml.probability.distributions

import breeze.numerics._

import math.{Pi, log1p}
import breeze.stats.distributions.{Moments, Rand, RandBasis}
import io.github.tailhq.dynaml.algebra._
import io.github.tailhq.dynaml.algebra.PartitionedMatrixOps._
import io.github.tailhq.dynaml.algebra.PartitionedMatrixSolvers._
import io.github.tailhq.dynaml.probability.GaussianRV
import scala.runtime.ScalaRunTime

/**
  * Represents a Gaussian distribution over a [[PartitionedVector]]
  *
  * @author tailhq
  */
case class BlockedMultiVariateGaussian(
  mean: PartitionedVector,
  covariance: PartitionedPSDMatrix)(implicit rand: RandBasis = Rand) extends
  AbstractContinuousDistr[PartitionedVector] with
  Moments[PartitionedVector, PartitionedPSDMatrix] with
  HasErrorBars[PartitionedVector] {

  def draw() = {
    val nE: Int = if(mean.rowBlocks > 1L) mean(0L to 0L)._data.head._2.length else mean.rows.toInt
    val z: PartitionedVector = PartitionedVector.rand(mean.rows, nE, GaussianRV(0.0, 1.0))
    val m: PartitionedVector = root * z
    m + mean
  }

  private lazy val root: LowerTriPartitionedMatrix = bcholesky(covariance)

  override def toString() =  ScalaRunTime._toString(this)

  override def unnormalizedLogPdf(t: PartitionedVector) = {
    val centered: PartitionedVector = t - mean
    val z: PartitionedVector = root \ centered
    val slv: PartitionedVector = root.t \ z
    val d: Double = slv dot centered
    -1.0*d/2.0

  }

  override lazy val logNormalizer = {
    // determinant of the cholesky decomp is the sqrt of the determinant of the cov matrix
    // this is the log det of the cholesky decomp
    val det = bsum(blog(bdiag(root)))
    mean.rows.toDouble/2 *  log(2 * Pi) + 0.5*det
  }

  def variance = covariance
  def mode = mean
  lazy val entropy = {
    mean.rows.toDouble * log1p(2 * Pi) + bsum(blog(bdiag(root)))
  }

  override def confidenceInterval(s: Double) = {
    val signFlag = if(s < 0) -1.0 else 1.0
    val nE: Int = if(mean.rowBlocks > 1L) mean(0L to 0L)._data.head._2.length else mean.rows.toInt

    val ones = PartitionedVector.ones(mean.rows, nE)
    val multiplier = signFlag*s

    val bar: PartitionedVector = root*(ones*multiplier)

    (mean - bar, mean + bar)
  }
}

