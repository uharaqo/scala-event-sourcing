package com.github.uharaqo.es

import cats.effect.IO

class DefaultStateProvider(eventReader: EventReader) extends StateProvider {
  import cats.implicits.*

  override def load[S, E](info: StateInfo[S, E], id: AggId): IO[VersionedState[S]] =
    eventReader(AggInfo(info.name, id)).compile
      .fold(VersionedState(0, info.emptyState).pure[IO]) { (prevState, e) =>
        for
          prev  <- prevState
          event <- info.eventDeserializer(e.event)
          next  <- info.eventHandler(prev.state, event).pure[IO]
        yield VersionedState(prev.version + 1, next)
      }
      .flatten
}
