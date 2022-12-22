package io.github.uharaqo.es.example.grpc

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.github.uharaqo.es.*
import io.github.uharaqo.es.example.*
import io.github.uharaqo.es.grpc.server.{GrpcAggregateInfo, GrpcServer}
import io.github.uharaqo.es.impl.repository.{DoobieEventRepository, H2TransactorFactory}
import io.github.uharaqo.es.proto.eventsourcing.*
import io.github.uharaqo.es.proto.example.*
import doobie.util.transactor.Transactor
import io.grpc.{Metadata, Status}

object Server extends IOApp {

  private val transactor = H2TransactorFactory.create()
  private val repository = (xa: Transactor[IO]) => DoobieEventRepository(xa)
  private val processor  = (xa: Transactor[IO]) => GrpcCommandProcessor(xa)
  override def run(args: List[String]): IO[ExitCode] = {
    // TODO: projection
    val serverFactory = { (xa: Transactor[IO]) => processor(xa).server }
    (for
      xa <- transactor
      _  <- Resource.eval(repository(xa).initTables())
      // _    <- Resource.eval(UserRepository(xa).initTables())
      exit <- Resource.eval(serverFactory(xa).start)
    yield exit)
      .use(IO(_))
  }
}

private class GrpcCommandProcessor(xa: Transactor[IO]) {
  import com.github.benmanes.caffeine.cache.Caffeine
  import scalacache.AbstractCache
  import scalacache.caffeine.CaffeineCache

  private val repository = DoobieEventRepository(xa)
  private val env = new CommandProcessorEnv {
    override val stateProviderFactory =
      DefaultStateProviderFactory(
        repository.reader,
        new CacheFactory {
          override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
            CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
        },
        86_400_000L
      )
    override val eventWriter = repository.writer
  }
  private val userDeps  = new UserResource.Dependencies {}
  private val groupDeps = new GroupResource.Dependencies {}
  // private def registryFactory[D] = (info: GrpcAggregateInfo[?, ?, ?, D], dep: D) =>
  // info.commandRegistry(dep).view.mapValues(a => a.copy(handler = debug(a.handler)))
//   private val registry = (xa: Transactor[IO]) =>
//     (registryFactory(UserResource.info, userDeps(xa))
//       ++ registryFactory(GroupResource.info, groupDeps(xa))).toMap
  private val processors = Seq(
    PartialCommandProcessor(CommandInputParser(UserResource.info.commandInfoFactory(userDeps))),
    PartialCommandProcessor(CommandInputParser(GroupResource.info.commandInfoFactory(groupDeps))),
  )
  private val processor = CommandProcessor(env, processors)

  private val parser: SendCommandRequest => IO[CommandInput] = { req =>
    IO {
      val p = req.payload.get
      CommandInput(AggInfo(req.aggregate, req.id), p.typeUrl.split('/').last, p.value.toByteArray)
    }
      .handleErrorWith(t => IO.raiseError(Status.INVALID_ARGUMENT.withCause(t).asRuntimeException()))
  }

  private def handle(input: CommandInput): IO[CommandOutput] =
    processor(input)
      .map(records => records.lastOption.getOrElse(throw Status.UNKNOWN.asRuntimeException()))
      .map(r => CommandOutput(r.version, "TODO"))

  private val errorHandler = (t: Throwable) =>
    t.printStackTrace() // TODO
    IO.raiseError(
      t match
        case _ =>
          Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException()
      // TODO: why code 200?
    )

  val server = GrpcServer(
    new GrpcCommandHandlerFs2Grpc[IO, Metadata] {
      override def sendCommand(request: SendCommandRequest, ctx: Metadata): IO[SendCommandResponse] =
        (for
          parsed <- parser(request)
          result <- handle(parsed)
        yield SendCommandResponse(result.version, result.message))
          .handleErrorWith(errorHandler)

      override def loadState(request: LoadStateRequest, ctx: Metadata): IO[LoadStateResponse] = ???
    }
  )
}
//
// private def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
//   (s, c, ctx) =>
//     for
//       _ <- IO.println(s"Command: $c")
//       r <- commandHandler(s, c, ctx)
//       _ <- IO.println(s"Response: $r")
//     yield r
