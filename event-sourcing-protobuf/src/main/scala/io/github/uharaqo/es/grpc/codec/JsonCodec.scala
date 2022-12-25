package io.github.uharaqo.es.grpc.codec

import cats.effect.IO
import io.github.uharaqo.es.{Bytes, Codec}
import scalapb.json4s.JsonFormat
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import java.nio.charset.StandardCharsets.UTF_8

/** To use this class, include scalapb-json4s: [[https://github.com/scalapb/scalapb-json4s]]
  *
  * @tparam A
  *   type to be converted
  */
class JsonCodec[A <: GeneratedMessage: GeneratedMessageCompanion] extends Codec[A] {
  override def convert(v: A): IO[Bytes] =
    IO(JsonFormat.toJsonString(v).getBytes(UTF_8))
  override def convert(bytes: Bytes): IO[A] =
    IO(JsonFormat.fromJsonString[A](new String(bytes, UTF_8)))
}
