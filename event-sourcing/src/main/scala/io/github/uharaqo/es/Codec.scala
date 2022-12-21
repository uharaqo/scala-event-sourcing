package io.github.uharaqo.es

import cats.effect.IO

trait Serializer[T]:
  def apply(v: T): IO[Bytes]

trait Deserializer[T]:
  def apply(bytes: Bytes): IO[T]

trait Codec[T]:
  val serializer: Serializer[T]
  val deserializer: Deserializer[T]
