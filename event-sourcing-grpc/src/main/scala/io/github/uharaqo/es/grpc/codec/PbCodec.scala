package io.github.uharaqo.es.grpc.codec

import cats.effect.IO
import io.github.uharaqo.es.*
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import scalapb.TypeMapper

object PbCodec {
  def apply[A <: GeneratedMessage](using cmp: GeneratedMessageCompanion[A]) = new Codec[A] {
    val serializer: Serializer[A]     = PbSerializer[A]
    val deserializer: Deserializer[A] = PbDeserializer[A]

    override def apply(v: A): IO[Bytes]     = serializer(v)
    override def apply(bytes: Bytes): IO[A] = deserializer(bytes)
  }

  class PbSerializer[A <: GeneratedMessage] extends Serializer[A]:
    override def apply(v: A): IO[Bytes] = IO(v.toByteArray)

  class PbDeserializer[A <: GeneratedMessage](using cmp: GeneratedMessageCompanion[A]) extends Deserializer[A]:
    override def apply(bytes: Bytes): IO[A] = IO(cmp.parseFrom(bytes))

  def toPbMessage[M, T](content: T)(using mapper: TypeMapper[M, T]) = mapper.toBase(content)
}
