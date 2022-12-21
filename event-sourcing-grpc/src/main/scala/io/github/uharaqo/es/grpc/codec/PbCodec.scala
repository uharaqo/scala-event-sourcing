package io.github.uharaqo.es.grpc.codec

import cats.effect.IO
import io.github.uharaqo.es.*
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import scalapb.TypeMapper

object PbCodec {
  def apply[T <: GeneratedMessage](using cmp: GeneratedMessageCompanion[T]) = new Codec[T] {
    override val serializer: Serializer[T]     = PbSerializer[T]
    override val deserializer: Deserializer[T] = PbDeserializer[T]
  }

  class PbSerializer[A <: GeneratedMessage] extends Serializer[A]:
    override def apply(v: A): IO[Bytes] = IO(v.toByteArray)

  class PbDeserializer[A <: GeneratedMessage](using cmp: GeneratedMessageCompanion[A]) extends Deserializer[A]:
    override def apply(bytes: Bytes): IO[A] = IO(cmp.parseFrom(bytes))

  def toPbMessage[M, T](content: T)(using mapper: TypeMapper[M, T]) = mapper.toBase(content)
}
