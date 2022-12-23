package io.github.uharaqo.es

import cats.effect.IO

trait Serializer[A]:
  def apply(v: A): IO[Bytes]

trait Deserializer[A]:
  def apply(bytes: Bytes): IO[A]

trait Codec[A] extends Serializer[A] with Deserializer[A]
