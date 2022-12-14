package io.github.uharaqo.es

import cats.implicits.*
import cats.effect.IO
import fs2.Stream

case class EventQuery(name: AggName, lastSeqId: SeqId)

trait EventWriter:

  /** Persist events into a storage. All events must be persisted atomically; 0 or all records must be written.
    *
    * @param output
    *   events
    * @return
    *   true on success; false on conflict
    */
  def write(output: CommandOutput): IO[Boolean] // TODO: just raise exception?

trait EventReader:
  /** Load all events that has a larger version than the [[previousVersion]].
    *
    * @param name
    *   aggregate name
    * @param id
    *   aggregate ID
    * @param previousVersion
    *   the version kept in the process
    * @return
    *   event stream
    */
  def queryById(name: AggName, id: AggId, previousVersion: Version): Stream[IO, VersionedEvent]

  /** Load all events that has a larger seqId than the [[query.lastSeqId]]
    *
    * @param query
    *   aggregate name and seqId
    * @return
    *   event stream
    */
  def queryByName(query: EventQuery): Stream[IO, EventRecord]

extension (reader: EventReader) {
  def loadRecords[E](stateInfo: StateInfo[?, E], lastSeqId: Long = 0L): Stream[IO, (EventRecord, E)] =
    reader
      .queryByName(EventQuery(stateInfo.name, lastSeqId))
      .evalMap(r => stateInfo.eventCodec.convert(r.event).map(e => r -> e))

  def loadEvents[E](stateInfo: StateInfo[?, E], id: AggId, previousVersion: Long = 0L): Stream[IO, (Version, E)] =
    reader
      .queryById(stateInfo.name, id, previousVersion)
      .evalMap(ve => stateInfo.eventCodec.convert(ve.event).map(e => ve.version -> e))
}

trait EventRepository extends EventReader with EventWriter
