package com.github.uharaqo.es.example.grpc

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.github.uharaqo.es.*
import com.github.uharaqo.es.example.{GroupResource, UserResource}
import com.github.uharaqo.es.grpc.server.GrpcServer
import com.github.uharaqo.es.impl.repository.{DoobieEventRepository, H2TransactorFactory}
import com.github.uharaqo.es.proto.eventsourcing.*
import com.github.uharaqo.es.proto.example.*
import doobie.util.transactor.Transactor
import io.grpc.{Metadata, Status}

object Server extends IOApp {
  private val registry =
    (UserResource.newCommandRegistry().view.mapValues(a => a.copy(handler = debug(a.handler)))
      ++ GroupResource.newCommandRegistry().view.mapValues(a => a.copy(handler = debug(a.handler)))).toMap
  private val transactor = H2TransactorFactory.create()
  private val repository = (xa: Transactor[IO]) => DoobieEventRepository(xa)
  private val processor  = (repo: EventRepository) => GrpcCommandProcessor(registry, repo)
  private val parser: SendCommandRequest => IO[CommandRequest] = { req =>
    IO {
      val p = req.payload.get
      CommandRequest(AggInfo(req.aggregate, req.id), p.typeUrl.split('/').last, p.value.toByteArray)
    }
      .handleErrorWith(t => IO.raiseError(Status.INVALID_ARGUMENT.withCause(t).asRuntimeException()))
  }
  private val server =
    (processor: GrpcCommandProcessor) =>
      GrpcServer(
        new CommandHandlerFs2Grpc[IO, Metadata] {
          override def send(request: SendCommandRequest, ctx: Metadata): IO[CommandReply] =
            for
              parsed <- parser(request)
              result <- processor.command(parsed)
            yield CommandReply(result.version, result.message)

          override def load(request: LoadStateRequest, ctx: Metadata): IO[StateReply] = ???
        }
      )

  override def run(args: List[String]): IO[ExitCode] =
    (for
      xa <- transactor
      repo = repository(xa)
      _    <- Resource.eval(repo.initTables())
      code <- Resource.eval((repository andThen processor andThen server)(xa).start)
    yield code)
      .use(IO(_))
}

private class GrpcCommandProcessor(registry: CommandRegistry, repository: EventRepository) {
  import com.github.benmanes.caffeine.cache.Caffeine
  import scalacache.AbstractCache
  import scalacache.caffeine.CaffeineCache

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

private def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
  (s, c, ctx) =>
    for
      _ <- IO.println(s"Command: $c")
      r <- commandHandler(s, c, ctx)
      _ <- IO.println(s"Response: $r")
    yield r
