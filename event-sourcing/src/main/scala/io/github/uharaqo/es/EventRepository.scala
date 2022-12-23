package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

trait EventWriter:
  /** returns true on success; false on conflict */
  def write(records: EventRecords): IO[Boolean] // TODO: just raise exception?

trait EventReader:
  def load(info: AggInfo, previousVersion: Version): Stream[IO, VersionedEvent]

trait EventRepository extends EventReader with EventWriter
