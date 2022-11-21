package com.github.uharaqo.es.io.json

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import java.nio.charset.StandardCharsets
import io.circe.{Decoder, Encoder}

trait JsonCodec[T] {
  def encode(v: T): IO[ByteArray]
  def decode(json: ByteArray): IO[T]
}

class DefaultJsonCodec[T](
  private val encoder: T => IO[ByteArray],
  private val decoder: ByteArray => IO[T]
) extends JsonCodec[T] {
  override def encode(v: T): IO[ByteArray]    = encoder(v)
  override def decode(json: ByteArray): IO[T] = decoder(json)
}

object JsonCodec {

  def apply[T]()(implicit encoder: Encoder[T], decoder: Decoder[T]): JsonCodec[T] =
    new DefaultJsonCodec[T](getEncoder[T](), getDecoder[T]())

  def getEncoder[T]()(implicit encoder: Encoder[T]): T => IO[ByteArray] = { t =>
    IO(encoder(t).noSpaces.getBytes(StandardCharsets.UTF_8))
  }

  def getDecoder[T]()(implicit decoder: Decoder[T]): ByteArray => IO[T] =
    (json: ByteArray) =>
      IO.fromEither(
        io.circe.parser
          .parse(String(json, StandardCharsets.UTF_8))
          .map(decoder.decodeJson(_))
          .flatten
      )
}

object CommandDecoder {
  def forCommand[C]: Builder[C] = DefaultBuilder[C](List.empty[Decoder[C]])

  trait Builder[C] {
    def withCommand[CC <: C](implicit instance: Decoder[CC]): Builder[C]

    def build: ByteArray => IO[C]
  }

  private class DefaultBuilder[C](private val l: List[Decoder[C]] = List.empty) extends Builder[C] {
    override def withCommand[CC <: C](implicit instance: Decoder[CC]): Builder[C] =
      DefaultBuilder(instance.widen :: l)

    override def build: ByteArray => IO[C] =
      JsonCodec.getDecoder()(l.reduce(_ or _))
  }
}
