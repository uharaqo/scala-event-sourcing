package com.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

/** returns true on success; false on conflict */
type EventWriter = Seq[EventRecord] => IO[Boolean]
type EventReader = (AggInfo, Version) => Stream[IO, VersionedEvent]

trait EventRepository:
  val writer: EventWriter
  val reader: EventReader

case class EventRecord(name: AggName, id: AggId, version: Version, timestamp: TsMs, event: Bytes):

  import java.nio.charset.StandardCharsets.UTF_8
  override def toString(): String =
    s"EventRecord($name,$id,$version,$timestamp,${String(event, UTF_8)})"

import cats.implicits.*
extension (repo: EventRepository) {
  def dump[E](aggInfo: AggInfo, eventDeserializer: Deserializer[E]) =
    repo
      .reader(aggInfo, 0)
      .through(_.map(ve => eventDeserializer(ve.event).map(v => println(s"${ve.version}: $v"))))
      .compile
      .toList
      .flatMap(_.sequence)
}
