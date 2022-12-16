package com.github.uharaqo.es

import cats.effect.IO
import fs2.Stream

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
      .fold(IO.pure(initialState)) { (prevState, e) =>
        for
          prev  <- prevState
          event <- info.eventDeserializer(e.event)
          next  <- IO.pure(info.eventHandler(prev.state, event))
        yield next.map(VersionedState(prev.version + 1, _)).getOrElse(prev)
      }
      .flatten
}

object MemoisedStateProviderFactory {
  import java.util.concurrent.ConcurrentHashMap

  private val m = ConcurrentHashMap[StateInfo[_, _], StateProvider[_]]()

  def apply(stateProviderFactory: StateProviderFactory) =
    new StateProviderFactory {
      override def create[S, E](info: StateInfo[S, E]): StateProvider[S] =
        m.computeIfAbsent(info, _ => stateProviderFactory.create(info)).asInstanceOf[StateProvider[S]]
    }
}

class EventReaderStateProviderFactory(eventReader: EventReader) extends StateProviderFactory {

  override def create[S, E](info: StateInfo[S, E]): StateProvider[S] = { (id, prev) =>
    val events = eventReader(AggInfo(info.name, id), prev.map(_.version).getOrElse(0L))

    info.nextState(prev, events)
  }
}

trait StateCache[S]:
  def get(id: AggId): IO[Option[VersionedState[S]]]
  def set(id: AggId, state: VersionedState[S]): IO[Unit]

trait StateCacheFactory:
  def create[S, E](info: StateInfo[S, E]): StateCache[S]

object DefaultStateProviderFactory {
  import java.util.concurrent.TimeUnit
  import scala.concurrent.duration.Duration

  def apply(eventReader: EventReader, cacheFactory: CacheFactory, ttlMillis: Long): StateProviderFactory =
    CachedStateProviderFactory(
      EventReaderStateProviderFactory(eventReader),
      ScalaCacheFactory(cacheFactory, Some(Duration(ttlMillis, TimeUnit.MILLISECONDS)))
    ).memoise
}

class CachedStateProviderFactory(
  originalFactory: StateProviderFactory,
  cacheFactory: StateCacheFactory
) extends StateProviderFactory {
  import cats.implicits.*

  override def create[S, E](info: StateInfo[S, E]): StateProvider[S] = {
    val stateCache = cacheFactory.create(info)
    val original   = originalFactory.create(info)

    new StateProvider[S]:
      override def load(id: AggId, prevState: Option[VersionedState[S]]): IO[VersionedState[S]] =
        for
          o <- stateCache.get(id)
          v <- original.load(id, o.orElse(prevState)) // TODO: choose one with a larger version
          _ <- stateCache.set(id, v)
        yield v

      override def afterWrite(id: AggId, prevState: VersionedState[S], responses: Seq[EventRecord]): IO[Unit] =
        info.nextState(Some(prevState), Stream(responses.map(r => VersionedEvent(r.version, r.event)): _*))
          >>= { stateCache.set(id, _) }
  }
}

import scalacache.*

import scala.concurrent.duration.Duration

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
