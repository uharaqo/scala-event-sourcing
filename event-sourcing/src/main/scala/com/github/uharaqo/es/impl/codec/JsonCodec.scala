package com.github.uharaqo.es.impl.codec

import cats.effect.IO
import com.github.uharaqo.es.*

object JsonCodec:
  import com.github.plokhotnyuk.jsoniter_scala.core.*
  import com.github.plokhotnyuk.jsoniter_scala.macros.*

  def apply[T]()(using codec: JsonValueCodec[T]): Codec[T] =
    new Codec[T] {
      override val serializer: Serializer[T]     = v => IO(writeToArray(v))
      override val deserializer: Deserializer[T] = bs => IO(readFromArray(bs))
    }
