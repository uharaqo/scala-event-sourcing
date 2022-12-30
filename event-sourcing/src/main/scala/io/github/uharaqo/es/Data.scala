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
