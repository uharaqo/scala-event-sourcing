package com.github.uharaqo.es.eventprojection

import cats.effect.IO
import com.github.uharaqo.es.Serde.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import fs2.Stream

object EventProjections {

  // handler
  sealed class ProjectionException protected (message: String, cause: Throwable) extends Exception(message, cause)
  class UnrecoverableException(message: String, cause: Throwable) extends ProjectionException(message, cause):
    def this(message: String) = this(message, null: Throwable)
  class TemporaryException(message: String, cause: Throwable) extends ProjectionException(message, cause):
    def this(message: String) = this(message, null: Throwable)

  type ProjectionResult[S] = Either[ProjectionException, S]

  // repository
  case class EventRecord(id: ResourceIdentifier, version: Version, timestamp: TsMs, event: Bytes)
  case class ProjectionEvent[E](id: ResourceIdentifier, version: Version, timestamp: TsMs, event: E)
  case class EventQuery(resourceName: ResourceName, lastTimestamp: TsMs)

  trait ProjectionRepository:
    def load(query: EventQuery): Stream[IO, EventRecord]

  type Projection[E, S] = ProjectionEvent[E] => IO[ProjectionResult[S]]
}
