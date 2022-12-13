package com.github.uharaqo.es.impl.codec

import cats.effect.IO
import com.github.uharaqo.es.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

class JsonCodec[A](using codec: JsonValueCodec[A]) extends Codec[A]:
  override val serializer: Serializer[A]     = v => IO(writeToArray(v))
  override val deserializer: Deserializer[A] = bytes => IO(readFromArray(bytes))
