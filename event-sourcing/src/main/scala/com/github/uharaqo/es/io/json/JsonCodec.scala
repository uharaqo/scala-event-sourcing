package com.github.uharaqo.es.io.json

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.Serialized
import java.nio.charset.StandardCharsets.UTF_8

trait JsonCodec[T]:
  def encode(v: T): IO[Serialized]
  def decode(json: Serialized): IO[T]

object JsonCodec:
  import com.github.plokhotnyuk.jsoniter_scala.macros._
  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonCodec => _, _}

  def apply[T]()(using codec: JsonValueCodec[T]): JsonCodec[T] =
    new JsonCodec[T] {
      override def encode(v: T): IO[Serialized]    = IO(String(writeToArray(v), UTF_8))
      override def decode(json: Serialized): IO[T] = IO(readFromArray(json.getBytes(UTF_8)))
    }
