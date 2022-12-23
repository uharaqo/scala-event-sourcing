package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

trait EventWriter:
  /** returns true on success; false on conflict */
  def apply(records: EventRecords): IO[Boolean] // TODO: just raise exception?

trait EventReader:
  def apply(info: AggInfo, previousVersion: Version): Stream[IO, VersionedEvent]

trait EventRepository:
  val writer: EventWriter
  val reader: EventReader
