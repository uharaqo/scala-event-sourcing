package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

type Projection[E, S] = ProjectionEvent[E] => IO[ProjectionResult[S]]

type ProjectionResult[S] = Either[ProjectionException, S]

trait ProjectionRepository:
  def load(query: EventQuery): Stream[IO, EventRecord]

case class ProjectionEvent[E](id: AggId, version: Version, timestamp: TsMs, event: E)

case class EventQuery(name: AggName, lastTimestamp: TsMs)
