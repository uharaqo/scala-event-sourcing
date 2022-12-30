package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

trait Projection[E]:
  def apply(event: ProjectionEvent[E]): IO[ProjectionResult]

case class ProjectionEvent[E](id: AggId, version: Version, seqId: SeqId, event: E)

type ProjectionResult = Either[ProjectionException, Unit]
