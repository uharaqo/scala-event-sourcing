package io.github.uharaqo.es

import cats.effect.IO

trait StateLoader[S]:
  def load(id: AggId): IO[VersionedState[S]] = load(id, None)
  def load(id: AggId, prevState: Option[VersionedState[S]]): IO[VersionedState[S]]
  def onSuccess(id: AggId, prevState: VersionedState[S], records: EventRecords): IO[Unit] = IO.unit

trait StateLoaderFactory:
  def apply[S, E](info: StateInfo[S, E]): IO[StateLoader[S]]

case class StateInfo[S, E](
  name: AggName,
  emptyState: S,
  eventCodec: Codec[E],
  eventHandler: EventHandler[S, E],
):
  override def toString(): String = name
