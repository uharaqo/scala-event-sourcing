package com.github.uharaqo.es.example.grpc

import cats.effect.IO
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.uharaqo.es.*
import io.grpc.Status
import scalacache.AbstractCache
import scalacache.caffeine.CaffeineCache
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, TimeUnit}

class GrpcCommandProcessor(registry: CommandRegistry, repository: EventRepository) {

  private val stateProvider =
    DefaultStateProviderFactory(
      repository.reader,
      new CacheFactory {
        override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
          CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
      },
      86_400_000L
    )
  private val processor = CommandProcessor(registry, stateProvider, repository.writer)

  def command(request: CommandRequest): IO[CommandResult] =
    processor(request)
      .map(records => records.lastOption.getOrElse(throw Status.UNKNOWN.asRuntimeException()))
      .map(r => CommandResult(r.version, "TODO"))
}
