package io.github.uharaqo.es

import cats.effect.IO

type Serializer[T]   = T => IO[Bytes]
type Deserializer[T] = Bytes => IO[T]

trait Codec[T]:
  val serializer: Serializer[T]
  val deserializer: Deserializer[T]
