package io.github.uharaqo.es.repository

import cats.effect.{IO, Resource, Sync}
import cats.free.Free
import doobie.*
import doobie.implicits.*
import doobie.syntax.ConnectionIOOps
import doobie.util.transactor.Transactor
import io.github.uharaqo.es.*

class DoobieEventRepository(xa: Transactor[IO]) extends EventRepository, ProjectionRepository {

  import DoobieEventRepository.*
  import cats.implicits.*
  import doobie.*
  import doobie.postgres.*
  import doobie.postgres.implicits.*
  import doobie.util.fragment.Fragment
  import fs2.Stream
  import org.typelevel.log4cats.Logger
  import org.typelevel.log4cats.slf4j.Slf4jLogger

  implicit def logger: Logger[IO] = Slf4jLogger.getLogger[IO]
  // query log is enabled by adding this line
  // implicit val han: LogHandler = LogHandler.jdkLogHandler

  def initTables(): IO[Unit] =
    Seq(CREATE_EVENTS_TABLE, CREATE_PROJECTION_TABLE).traverse(_.update.run).transact(xa).void

  override def write(output: CommandOutput) =
    // PK: (name, id, ver) prevents conflicts
    Update[EventOutput](INSERT_EVENT)
      .updateMany(output.events)
      .transact(xa)
      // Return false on a PK conflict
      .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION => false }
      // throw error on any other exceptions
      .handleErrorWith(t => IO.raiseError(EsException.EventStoreFailure(t)))
      // Return true if all the records were applied or there was no record
      .map(z => z.map(_ == output.events.size).fold(b => true, b => b))

  override def queryById(name: AggName, id: AggId, previousVersion: Version): Stream[IO, VersionedEvent] =
    SELECT_EVENTS(name, id, previousVersion)
      .query[VersionedEvent]
      .stream
      .transact(xa)
      .handleErrorWith(t => Stream.raiseError(EsException.EventLoadFailure(t)))

  override def queryByName(query: EventQuery): Stream[IO, EventRecord] =
    SELECT_EVENTS_BY_NAME(query.name, query.lastSeqId).query[EventRecord].stream.transact(xa)

  override def runWithLock(projectionId: ProjectionId)(task: SeqId => IO[Option[SeqId]]): IO[Boolean] =
    WeakAsync
      .liftK[IO, ConnectionIO]
      .use { lift =>
        val queries = for
          prevTs <- LOCK_PROJECTION(projectionId)
            .query[Long]
            .option
            .flatMap {
              case Some(ts) => Free.pure(ts)
              case None =>
                INSERT_PROJECTION(projectionId).run
                  >> LOCK_PROJECTION(projectionId).query[Long].option.map(_.get)
            }
          lastTs <- lift(task(prevTs))
          result <- lastTs match
            case None => Free.pure(false)
            case Some(lastTs) =>
              Update[(SeqId, String, SeqId)](UPDATE_PROJECTION)
                .run((lastTs, projectionId, prevTs))
                .map(_ > 0)
        yield result

        queries.transact(xa)
      }
      .handleErrorWith(t => logger.error(t)(s"Failed to run projection for $projectionId") >> IO.pure(false))
}

object DoobieEventRepository {

  // seq is an ID across all events. it can be replaced by a sequential ID
  private val CREATE_EVENTS_TABLE =
    sql"""CREATE TABLE IF NOT EXISTS events (
      name VARCHAR(15) NOT NULL,
      id VARCHAR(127) NOT NULL,
      ver BIGINT NOT NULL,
      event BYTEA NOT NULL,
      seq BIGSERIAL NOT NULL UNIQUE,
      created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (current_timestamp AT TIME ZONE 'UTC'),
      PRIMARY KEY (name, id, ver)
    )"""

  private val INSERT_EVENT =
    "INSERT INTO events (name, id, ver, event) VALUES (?, ?, ?, ?)"

  private val SELECT_EVENTS =
    (name: AggName, id: AggId, prevVer: Version) =>
      sql"""SELECT ver, event FROM events WHERE name = $name AND id = $id AND ver > $prevVer ORDER BY ver"""

  private val SELECT_EVENTS_BY_NAME =
    (name: AggName, prevSeq: SeqId) =>
      sql"""SELECT name, id, ver, seq, event FROM events WHERE name = $name AND $prevSeq < seq ORDER BY seq"""

  private val CREATE_PROJECTION_TABLE =
    sql"""CREATE TABLE IF NOT EXISTS projections (
    id VARCHAR(127) NOT NULL,
    seq BIGINT NOT NULL,
    PRIMARY KEY (id)
  )"""

  private val LOCK_PROJECTION =
    (id: ProjectionId) => sql"""SELECT seq FROM projections WHERE id = $id LIMIT 1 FOR UPDATE NOWAIT"""

  private val INSERT_PROJECTION =
    (id: ProjectionId) => sql"""INSERT INTO projections (id, seq) VALUES ($id, 0) ON CONFLICT DO NOTHING""".update

  private val UPDATE_PROJECTION =
    """UPDATE projections SET seq = ? WHERE id = ? AND seq = ?"""
}
