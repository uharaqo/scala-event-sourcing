package com.github.uharaqo.es.example.grpc

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.github.uharaqo.es.*
import com.github.uharaqo.es.example.*
import com.github.uharaqo.es.grpc.server.{GrpcAggregateInfo, GrpcServer}
import com.github.uharaqo.es.impl.repository.{DoobieEventRepository, H2TransactorFactory}
import com.github.uharaqo.es.proto.eventsourcing.*
import com.github.uharaqo.es.proto.example.*
import doobie.util.transactor.Transactor
import io.grpc.{Metadata, Status}

object Server extends IOApp {
  private val userDeps  = (xa: Transactor[IO]) => new UserResource.Dependencies {}
  private val groupDeps = (xa: Transactor[IO]) => new GroupResource.Dependencies {}

  private val transactor = H2TransactorFactory.create()
  private def registryFactory[D] = (info: GrpcAggregateInfo[_, _, _, D], dep: D) =>
    info.commandRegistry(dep).view.mapValues(a => a.copy(handler = debug(a.handler)))
  private val registry = (xa: Transactor[IO]) =>
    (registryFactory(UserResource.info, userDeps(xa))
      ++ registryFactory(GroupResource.info, groupDeps(xa))).toMap
  private val repository = (xa: Transactor[IO]) => DoobieEventRepository(xa)
  private val processor  = (repo: EventRepository, registry: CommandRegistry) => GrpcCommandProcessor(registry, repo)
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
        new GrpcCommandHandlerFs2Grpc[IO, Metadata] {
          override def send(request: SendCommandRequest, ctx: Metadata): IO[CommandReply] =
            (for
              parsed <- parser(request)
              result <- processor.command(parsed)
            yield CommandReply(result.version, result.message))
              .handleErrorWith(errorHandler)

          override def load(request: LoadStateRequest, ctx: Metadata): IO[StateReply] = ???
        }
      )
  private val errorHandler = (t: Throwable) =>
    t.printStackTrace() // TODO
    IO.raiseError(
      t match
        case _ =>
          Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException()
      // TODO: why code 200?
    )

  override def run(args: List[String]): IO[ExitCode] = {
    // TODO: projection
    val serverFactory = { (xa: Transactor[IO]) => server(processor(repository(xa), registry(xa))) }
    (for
      xa <- transactor
      _  <- Resource.eval(repository(xa).initTables())
      // _    <- Resource.eval(UserRepository(xa).initTables())
      exit <- Resource.eval(serverFactory(xa).start)
    yield exit)
      .use(IO(_))
  }
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
