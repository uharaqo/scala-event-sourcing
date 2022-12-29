package io.github.uharaqo.es

import cats.effect.IO
import fs2.Stream
import cats.effect.kernel.Ref

extension [S, E](info: StateInfo[S, E]) {
  def nextState(
    previousState: Option[VersionedState[S]],
    events: Stream[IO, VersionedEvent]
  ): IO[VersionedState[S]] =
    val initialState =
      previousState match
        case Some(value) => value
        case None        => VersionedState(0L, info.emptyState)

    events.compile
      .fold(IO.pure(initialState)) { (prevState, ve) =>
        for
          prevVerS <- prevState
          nextE    <- info.eventCodec.convert(ve.event)
          nextS    <- IO.pure(info.eventHandler(prevVerS.state, nextE))
        yield VersionedState(ve.version, nextS.getOrElse(prevVerS.state))
      }
      .flatten
}

class EventReaderStateLoaderFactory(eventReader: EventReader) extends StateLoaderFactory {

  override def apply[S, E](info: StateInfo[S, E]): IO[StateLoader[S]] =
    IO.pure(
      new StateLoader[S] {
        override def load(id: AggId, prevState: Option[VersionedState[S]]): IO[VersionedState[S]] = {
          val events = eventReader.queryById(info.name, id, prevState.map(_.version).getOrElse(0L))
          info.nextState(prevState, events)
        }
      }
    )
}

class CachedStateLoaderFactory(
  originalFactory: StateLoaderFactory,
  cacheFactory: StateCacheFactory
) extends StateLoaderFactory {
  import cats.implicits.*

  override def apply[S, E](info: StateInfo[S, E]): IO[StateLoader[S]] = {
    val stateCache = cacheFactory.create(info)

    IO.pure(new StateLoader[S]:
      override def load(id: AggId, prevState: Option[VersionedState[S]]): IO[VersionedState[S]] =
        for
          original <- originalFactory(info)
          o        <- stateCache.get(id)
          v        <- original.load(id, o.orElse(prevState)) // TODO: choose one with a larger version
          _        <- stateCache.set(id, v)
        yield v

      override def onSuccess(id: AggId, prevState: VersionedState[S], records: EventRecords): IO[Unit] =
        info.nextState(Some(prevState), Stream(records.map(r => VersionedEvent(r.version, r.event))*))
          >>= { stateCache.set(id, _) }
    )
  }
}

import scalacache.*
import scala.concurrent.duration.Duration

trait StateCache[S]:
  def get(id: AggId): IO[Option[VersionedState[S]]]
  def set(id: AggId, state: VersionedState[S]): IO[Unit]

trait StateCacheFactory:
  def create[S, E](info: StateInfo[S, E]): StateCache[S]

trait CacheFactory:
  def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]]

class ScalaCache[K, V](cache: AbstractCache[IO, K, V], defaultTtl: Option[Duration]):
  def get(k: K): IO[Option[V]]                         = cache.get(k)
  def set(k: K, v: V): IO[Unit]                        = set(k, v, defaultTtl)
  def set(k: K, v: V, ttl: Option[Duration]): IO[Unit] = cache.put(k)(v, ttl)

class ScalaCacheFactory(factory: CacheFactory, defaultTtl: Option[Duration]) extends StateCacheFactory {
  override def create[S, E](info: StateInfo[S, E]): StateCache[S] = {
    val cache: ScalaCache[AggId, VersionedState[S]] =
      ScalaCache(factory.create(info), defaultTtl)

    new StateCache[S]:
      override def get(id: AggId): IO[Option[VersionedState[S]]]      = cache.get(id)
      override def set(id: AggId, state: VersionedState[S]): IO[Unit] = cache.set(id, state)
  }
}
