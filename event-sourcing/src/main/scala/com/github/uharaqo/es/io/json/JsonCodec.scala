package com.github.uharaqo.es.io.json

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.Serialized
import java.nio.charset.StandardCharsets.UTF_8

trait JsonCodec[T] {
  def encode(v: T): IO[Serialized]
  def decode(json: Serialized): IO[T]
}

class DefaultJsonCodec[T](
  private val encoder: T => IO[Serialized],
  private val decoder: Serialized => IO[T]
) extends JsonCodec[T] {
  override def encode(v: T): IO[Serialized]    = encoder(v)
  override def decode(json: Serialized): IO[T] = decoder(json)
}

object JsonCodec {
  import com.github.plokhotnyuk.jsoniter_scala.macros._
  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonCodec => _, _}

  def apply[T]()(using codec: JsonValueCodec[T]): JsonCodec[T] =
    new DefaultJsonCodec[T](
      v => IO(String(writeToArray(v), UTF_8)),
      json => IO(readFromArray(json.getBytes(UTF_8)))
    )

  // import io.circe.{Decoder, Encoder}
  // def apply[T]()(using encoder: Encoder[T], decoder: Decoder[T]): JsonCodec[T] =
  //   new DefaultJsonCodec[T](getEncoder[T](), getDecoder[T]())
  // def getEncoder[T]()(using encoder: Encoder[T]): T => IO[Serialized] = { t =>
  //   IO(encoder(t).noSpaces)
  // }
  // def getDecoder[T]()(using decoder: Decoder[T]): Serialized => IO[T] =
  //   (json: Serialized) =>
  //     IO.fromEither(
  //       io.circe.parser
  //         .parse(json)
  //         .map(decoder.decodeJson(_))
  //         .flatten
  //     )
}
