package com.github.uharaqo.es

import cats.effect.IO

trait StateProvider:
  def load[S, E](info: StateInfo[S, E], id: AggId): IO[VersionedState[S]]

case class StateInfo[S, E](
  name: AggName,
  emptyState: S,
  eventSerializer: Serializer[E],
  eventDeserializer: Deserializer[E],
  eventHandler: EventHandler[S, E],
)

object StateProvider:
  import cats.implicits.*

  def apply(eventReader: EventReader) =
    new StateProvider {
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
