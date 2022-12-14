package com.github.uharaqo.es.grpc.codec

import cats.effect.IO
import com.github.uharaqo.es.*
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

class PbSerializer[A <: GeneratedMessage]() extends (A => IO[Array[Byte]]):
  override def apply(v: A): IO[Array[Byte]] = IO(v.toByteArray)

class PbDeserializer[A <: GeneratedMessage](using cmp: GeneratedMessageCompanion[A]) extends (Array[Byte] => IO[A]):
  override def apply(bytes: Array[Byte]): IO[A] = IO(cmp.parseFrom(bytes))
