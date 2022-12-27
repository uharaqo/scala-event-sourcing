package io.github.uharaqo.es.repository

import cats.effect.{IO, Resource}
import io.github.uharaqo.es.*
import doobie.implicits.*
import doobie.util.transactor.Transactor

class DoobieEventRepository(xa: Transactor[IO]) extends EventRepository {

  import DoobieEventRepository.*
  import cats.implicits.*
  import doobie.*
  import doobie.postgres.*
  import doobie.postgres.implicits.*
  import doobie.util.fragment.Fragment
  import fs2.Stream

  def initTables(): IO[Unit] =
    Seq(CREATE_EVENTS_TABLE).traverse(_.update.run).transact(xa).void

  override def write(records: EventRecords) =
    Update[EventRecord](INSERT_EVENT)
      .updateMany(records)
      .transact(xa)
      // Return false on a PK conflict
      .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION => false }
      // throw error on any other exceptions
      .handleErrorWith(t => IO.raiseError(EsException.EventStoreFailure(t)))
      // Return true if all the records were applied or there was no record
      .map(z => z.map(_ == records.size).fold(b => true, b => b))

  override def load(info: AggInfo, previousVersion: Version): Stream[IO, VersionedEvent] =
    SELECT_EVENTS(info, previousVersion)
      .query[VersionedEvent]
      .stream
      .transact(xa)
      .handleErrorWith(t => Stream.raiseError(EsException.EventLoadFailure(t)))

  override def load(query: EventQuery): Stream[IO, EventRecord] =
    SELECT_EVENTS_BY_RESOURCE(query.name, query.lastTimestamp).query[EventRecord].stream.transact(xa)
}

object DoobieEventRepository {

  val CREATE_EVENTS_TABLE =
    sql"""CREATE TABLE IF NOT EXISTS events (
      name VARCHAR(15) NOT NULL,
      id VARCHAR(127) NOT NULL,
      ver BIGINT NOT NULL,
      ts_ms BIGINT NOT NULL UNIQUE,
      event BYTEA NOT NULL,
      PRIMARY KEY (name, id, ver)
    )"""

  val INSERT_EVENT = "INSERT INTO events (name, id, ver, ts_ms, event) VALUES (?, ?, ?, ?, ?)"
  val SELECT_EVENTS =
    (info: AggInfo, prevVer: Version) =>
      sql"""SELECT ver, event FROM events WHERE name = ${info.name} AND id = ${info.id} AND ver > ${prevVer} ORDER BY ver"""
  val SELECT_EVENTS_BY_RESOURCE =
    (name: AggName, timestampGt: TsMs) =>
      sql"""SELECT name, id, ver, ts_ms, event FROM events WHERE name = $name AND ts_ms > $timestampGt ORDER BY ts_ms"""
}
