package io.github.uharaqo.es.codec

import cats.effect.IO
import io.github.uharaqo.es.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

class JsonCodec[A](using codec: JsonValueCodec[A]) extends Codec[A]:
  override def convert(v: A): IO[Bytes]     = IO(writeToArray(v))
  override def convert(bytes: Bytes): IO[A] = IO(readFromArray(bytes))
