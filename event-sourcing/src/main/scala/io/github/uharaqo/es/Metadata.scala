package io.github.uharaqo.es

import cats.effect.IO

trait Metadata:
  def get[A](key: Metadata.Key[A]): IO[Option[A]]

object Metadata:
  class Key[A](val name: String, val codec: Codec[A]) {
    override def toString: String = name
    override def hashCode(): Int  = name.hashCode
    override def equals(obj: Any): Boolean =
      obj match
        case that: Key[?] => that.name == name
        case _            => false
  }
  object Key {
    class BinaryKey(override val name: String)
        extends Key[Bytes](
          name,
          new Codec[Bytes] {
            override def convert(v: Bytes): IO[Bytes] = IO.pure(v)
          }
        )
    import java.nio.charset.StandardCharsets.UTF_8
    case class StringKey(override val name: String)
        extends Key[String](
          name,
          new Codec[String] {
            override def convert(v: String): IO[Bytes]     = IO(v.getBytes(UTF_8))
            override def convert(bytes: Bytes): IO[String] = IO(String(bytes, UTF_8))
          }
        )
  }

  val empty = new Metadata {
    override def get[A](key: Key[A]): IO[Option[A]] = IO.pure(None)
  }

class SimpleMetadata(data: Map[Metadata.Key[?], Bytes]) extends Metadata {
  override def get[A](key: Metadata.Key[A]): IO[Option[A]] =
    data.get(key) match
      case Some(v) => key.codec.convert(v).map(Some(_))
      case None    => IO.pure(None)
}
