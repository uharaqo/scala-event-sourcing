package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

trait Projection[E, S]:
  def apply(event: ProjectionEvent[E]): IO[ProjectionResult[S]]

case class ProjectionEvent[E](id: AggId, version: Version, timestamp: TsMs, event: E)

type ProjectionResult[S] = Either[ProjectionException, S]
