package com.github.uharaqo.es.impl.repository

import cats.effect.{IO, Resource}
import com.github.uharaqo.es.*
import doobie.implicits.*
import doobie.util.transactor.Transactor

class DoobieEventRepository(xa: Transactor[IO]) extends EventRepository with ProjectionRepository {

  import DoobieEventRepository.*
  import cats.implicits.*
  import doobie.*
  import doobie.postgres.*
  import doobie.postgres.implicits.*
  import doobie.util.fragment.Fragment
  import fs2.Stream

  def initTables(): IO[Unit] =
    Seq(CREATE_EVENTS_TABLE).traverse(_.update.run).transact(xa).void

  override val writer: EventWriter = { responses =>
    Update[EventRecord](INSERT_EVENT)
      .updateMany(responses)
      .transact(xa)
      // Return false on a PK conflict
      .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION => false }
      // throw error on any other exceptions
      .handleErrorWith(t => IO.raiseError(EsException.EventStoreFailure(t)))
      // Return true if all the records were applied or there was no response
      .map(z => z.map(_ == responses.size).fold(b => true, b => b))
  }
  override val reader: EventReader = { (info, prevVer) =>
    SELECT_EVENTS(info, prevVer)
      .query[VersionedEvent]
      .stream
      .transact(xa)
      .handleErrorWith(t => Stream.raiseError(EsException.EventLoadFailure(t)))
  }

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
