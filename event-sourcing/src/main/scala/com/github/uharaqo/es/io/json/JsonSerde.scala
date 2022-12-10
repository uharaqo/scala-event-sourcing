package com.github.uharaqo.es.io.json

import cats.effect.IO
import com.github.uharaqo.es.Serde
import com.github.uharaqo.es.Serde.*

object JsonSerde:
  import com.github.plokhotnyuk.jsoniter_scala.macros.*
  import com.github.plokhotnyuk.jsoniter_scala.core.*

  def apply[T]()(using codec: JsonValueCodec[T]): Serde[T] =
    new Serde[T] {
      override val serializer: Serializer[T]     = v => IO(writeToArray(v))
      override val deserializer: Deserializer[T] = bs => IO(readFromArray(bs))
    }
