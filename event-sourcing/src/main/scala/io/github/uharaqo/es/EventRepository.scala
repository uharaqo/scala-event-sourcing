package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

/** returns true on success; false on conflict */
type EventWriter = EventRecords => IO[Boolean]
type EventReader = (AggInfo, Version) => Stream[IO, VersionedEvent]

trait EventRepository:
  val writer: EventWriter
  val reader: EventReader
