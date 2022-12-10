package com.github.uharaqo.es

import cats.effect.IO

/** Generate the next state based on a previous state and next event */
type EventHandler[S, E] = (S, E) => S

trait StateProvider:
  def load[S, E](info: StateInfo[S, E], id: AggId): IO[VersionedState[S]]

case class StateInfo[S, E](
  name: AggName,
  emptyState: S,
  eventSerializer: Serializer[E],
  eventDeserializer: Deserializer[E],
  eventHandler: EventHandler[S, E],
)
