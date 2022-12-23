package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

trait Projection[E, S]:
  def apply(event: ProjectionEvent[E]): IO[ProjectionResult[S]]

trait ProjectionRepository:
  def load(query: EventQuery): Stream[IO, EventRecord]

case class ProjectionEvent[E](id: AggId, version: Version, timestamp: TsMs, event: E)

type ProjectionResult[S] = Either[ProjectionException, S]

case class EventQuery(name: AggName, lastTimestamp: TsMs)
