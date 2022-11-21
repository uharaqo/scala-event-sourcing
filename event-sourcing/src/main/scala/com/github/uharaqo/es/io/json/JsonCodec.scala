package com.github.uharaqo.es.io.json

import cats.effect.*
import cats.implicits.*

trait JsonCodec[T] {
  def encode(v: T): IO[String]
  def decode(json: String): IO[T]
}

class DefaultJsonCodec[T](
  private val encoder: T => IO[String],
  private val decoder: String => IO[T]
) extends JsonCodec[T] {
  override def encode(v: T): IO[String]    = encoder(v)
  override def decode(json: String): IO[T] = decoder(json)
}

object JsonCodec {
  def apply[T]()(implicit decoder: io.circe.Decoder[T]): JsonCodec[T] =
    new DefaultJsonCodec[T](getEncoder[T], getDecoder[T](decoder))

  def getEncoder[T]: T => IO[String] = {
    val encoder = io.circe.Encoder.encodeString.contramap[T](_.toString)
    t => IO(encoder(t).noSpaces)
  }

  def getDecoder[T](implicit decoder: io.circe.Decoder[T]): String => IO[T] =
    (json: String) => IO.fromEither(io.circe.parser.parse(json).map(decoder.decodeJson(_)).flatten)

  import io.circe.Decoder, io.circe.syntax._, io.circe.generic.auto._, cats.syntax.functor._
  class Builder[C](private val l: List[Decoder[C]] = List.empty) {

    inline def witha[CC <: C](implicit instance: Decoder[CC]): Builder[C] =
      Builder(instance.widen :: l)

    def build: String => IO[C] =
      JsonCodec.getDecoder(l.reduce(_ or _))
  }
}
