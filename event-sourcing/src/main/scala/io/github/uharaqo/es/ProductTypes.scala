package io.github.uharaqo.es

object ProductTypes {
  import scala.deriving.Mirror

  def from[A <: Product](value: A)(using mirror: Mirror.ProductOf[A]): mirror.MirroredElemTypes =
    Tuple.fromProductTyped(value)

  def to[A](value: Product)(using mirror: Mirror.ProductOf[A], ev: value.type <:< mirror.MirroredElemTypes): A =
    mirror.fromProduct(value)

  def convert[P <: Product, A](value: P)(using mirrorP: Mirror.ProductOf[P], mirrorA: Mirror.ProductOf[A]): A =
    mirrorA.fromProduct(Tuple.fromProductTyped(value))
}
