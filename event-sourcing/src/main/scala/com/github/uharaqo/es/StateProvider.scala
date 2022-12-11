package com.github.uharaqo.es

import cats.effect.IO

trait StateProvider[S] {
  def load(id: AggId): IO[VersionedState[S]] = load(id, None)
  def load(id: AggId, prevState: Option[VersionedState[S]]): IO[VersionedState[S]]
  def afterWrite(id: AggId, prevState: VersionedState[S], responses: Seq[EventRecord]): IO[Unit] = IO.unit
}

trait StateProviderFactory {
  def create[S, E](info: StateInfo[S, E]): StateProvider[S]
  def memoise: StateProviderFactory = MemoisedStateProviderFactory(this)
}

case class StateInfo[S, E](
  name: AggName,
  emptyState: S,
  eventSerializer: Serializer[E],
  eventDeserializer: Deserializer[E],
  eventHandler: EventHandler[S, E],
) {
  override def hashCode(): Int = name.hashCode
  override def equals(obj: Any): Boolean =
    obj match
      case null                 => false
      case obj: StateInfo[_, _] => this == obj || name.equals(obj.name)
      case _                    => false
}
