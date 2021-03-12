package io.github.tailhq.dynaml.kernels

import breeze.linalg.{DenseMatrix, DenseVector}

/**
 * Defines a trait which outlines the basic
 * functionality of Kernel Matrices.
 * */
trait KernelMatrix[T] extends Serializable {
  protected val kernel: T

  def eigenDecomposition(dimensions: Int): (DenseVector[Double], DenseMatrix[Double])

  def getKernelMatrix(): T = this.kernel
}
