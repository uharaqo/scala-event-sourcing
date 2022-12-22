package io.github.uharaqo.es.impl.codec

import cats.effect.IO
import io.github.uharaqo.es.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

class JsonCodec[A](using codec: JsonValueCodec[A]) extends Codec[A]:
  override def apply(v: A): IO[Bytes]     = IO(writeToArray(v))
  override def apply(bytes: Bytes): IO[A] = IO(readFromArray(bytes))
