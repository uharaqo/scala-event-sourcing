package io.github.uharaqo.es

type Bytes   = Array[Byte]
type Fqcn    = String
type Version = Long
type SeqId   = Long

type AggName = String
type AggId   = String

type EventRecords = Seq[EventRecord]

case class VersionedEvent(version: Version, event: Bytes)

case class VersionedState[S](version: Version, state: S)

case class EventRecord(name: AggName, id: AggId, version: Version, seqId: SeqId, event: Bytes):
  override def toString(): String =
    s"EventRecord($name, $id, $version, $seqId, ${String(event, java.nio.charset.StandardCharsets.UTF_8)})"

import cats.effect.IO

trait Metadata:
  def get[A](key: Metadata.Key[A]): IO[Option[A]]

object Metadata:
  class Key[A](val name: String, val codec: Codec[A]) {
    override def toString: String = name
    override def hashCode(): Int  = name.hashCode
    override def equals(obj: Any): Boolean =
      obj match
        case that: Key[A] => that.name == name
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
