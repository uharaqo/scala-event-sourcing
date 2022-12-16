package com.github.uharaqo.es.grpc.codec

import cats.effect.IO
import com.github.uharaqo.es.*
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

class PbSerializer[A <: GeneratedMessage] extends Serializer[A]:
  override def apply(v: A): IO[Bytes] = IO(v.toByteArray)

class PbDeserializer[A <: GeneratedMessage](using cmp: GeneratedMessageCompanion[A]) extends Deserializer[A]:
  override def apply(bytes: Bytes): IO[A] = IO(cmp.parseFrom(bytes))
