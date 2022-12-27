package io.github.uharaqo.es

import cats.implicits.*
import cats.effect.IO
import fs2.Stream

case class EventQuery(name: AggName, lastTimestamp: TsMs)

trait EventWriter:
  /** returns true on success; false on conflict */
  def write(records: EventRecords): IO[Boolean] // TODO: just raise exception?

trait EventReader:
  def load(info: AggInfo, previousVersion: Version): Stream[IO, VersionedEvent]

  def load(query: EventQuery): Stream[IO, EventRecord]

extension (reader: EventReader) {
  def loadRecords[E](stateInfo: StateInfo[?, E], lastTimestamp: Long = 0L): Stream[IO, (EventRecord, E)] =
    reader
      .load(EventQuery(stateInfo.name, lastTimestamp))
      .evalMap(r => stateInfo.eventCodec.convert(r.event).map(e => r -> e))

  def loadEvents[E](stateInfo: StateInfo[?, E], id: AggId, previousVersion: Long = 0L): Stream[IO, (Version, E)] =
    reader
      .load(AggInfo(stateInfo.name, id), previousVersion)
      .evalMap(ve => stateInfo.eventCodec.convert(ve.event).map(e => ve.version -> e))
}

trait EventRepository extends EventReader with EventWriter
