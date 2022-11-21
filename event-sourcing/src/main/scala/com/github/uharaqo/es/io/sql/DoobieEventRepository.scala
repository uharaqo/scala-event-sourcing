package com.github.uharaqo.es.io.sql

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javasql.*
import doobie.implicits.javatimedrivernative.*
import doobie.util.fragment.Fragment
import fs2.Stream

import java.time.Instant

class DoobieEventRepository(
  private val transactor: Resource[IO, Transactor[IO]]
) extends EventRepository {

  import DoobieEventRepository.*

  def initTables(): IO[Unit] =
    transactor.use { xa =>
      Seq(CREATE_EVENTS_TABLE).traverse(_.update.run).transact(xa).void
    }

  override val writer: EventWriter = { response =>
    transactor.use { xa =>
      val name = response.id.name
      val id   = response.id.id
      val now  = Instant.now()
      val records =
        response.events
          .map(e => EventRecord(name, id, e.version, now, e.event))

      import doobie.postgres._
      Update[EventRecord](INSERT_EVENT)
        .updateMany(records)
        .transact(xa)
        // Return false on a PK conflict
        .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION => false }
        // throw error on any other exceptions
        .handleErrorWith(t => IO.raiseError(EsException.EventStoreFailure(t)))
        // Return true iff all the records were applied
        .map(z => z.map(_ == records.size).fold(b => b, b => b))
    }
  }

  override val reader: EventReader = { resourceId =>
    (for {
      xa     <- Stream.resource(transactor)
      stream <- SELECT_EVENTS(resourceId).query[VersionedEvent].stream.transact(xa)
    } yield stream)
      .handleErrorWith(t => Stream.raiseError(EsException.EventLoadFailure(t)))
  }
}

object DoobieEventRepository {
  import doobie.implicits._
  import doobie.implicits.javasql._
  import doobie.implicits.javatimedrivernative._

  val CREATE_EVENTS_TABLE =
    sql"""CREATE TABLE events (
      name VARCHAR(15) NOT NULL,
      id VARCHAR(127) NOT NULL,
      ver BIGINT NOT NULL,
      timestamp TIMESTAMP NOT NULL,
      event VARCHAR(255) NOT NULL,
      PRIMARY KEY (name, id, ver)
    )"""

  val INSERT_EVENT = "INSERT INTO events (name, id, ver, timestamp, event) VALUES (?, ?, ?, ?, ?)"
  val SELECT_EVENTS =
    (resourceId: ResourceId) =>
      sql"""SELECT ver, event FROM events WHERE name = ${resourceId.name} AND id = ${resourceId.id}
          ORDER BY ver"""

  case class EventRecord(
    name: ResourceName,
    id: ResourceIdentifier,
    ver: Version,
    timestamp: Instant,
    event: SerializedEvent
  )

  // val createResourcesTable =
  //   sql"""CREATE TABLE resources (
  //     name VARCHAR(15) NOT NULL,
  //     id VARCHAR(127) NOT NULL,
  //     ver BIGINT NOT NULL,
  //     PRIMARY KEY (name, id)
  //   )"""
  // FOREIGN KEY (name, id) REFERENCES resources(name, id)
  // val INSERT_AGGREGATE = "INSERT INTO resources (name, id, ver) VALUES (?, ?, ?)"
}
