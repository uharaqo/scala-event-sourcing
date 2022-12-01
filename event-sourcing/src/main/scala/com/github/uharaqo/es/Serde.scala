package com.github.uharaqo.es

import cats.effect.IO

trait Serde[T]:
  import Serde.*
  val serializer: Serializer[T]
  val deserializer: Deserializer[T]

object Serde:
  type Bytes           = Array[Byte]
  type Serializer[T]   = T => IO[Bytes]
  type Deserializer[T] = Bytes => IO[T]
