package io.github.uharaqo.es

import cats.effect.IO

type ProjectionId = String

trait ProjectionRepository:
  def runWithLock(projectionId: ProjectionId)(task: SeqId => IO[Option[SeqId]]): IO[Boolean]
