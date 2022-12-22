package io.github.uharaqo.es.example.grpc

import cats.effect.{ExitCode, IO, IOApp, Resource}
import io.github.uharaqo.es.*
import io.github.uharaqo.es.example.*
import io.github.uharaqo.es.grpc.server.GrpcServer
import io.github.uharaqo.es.impl.repository.{DoobieEventRepository, H2TransactorFactory}
import io.github.uharaqo.es.proto.eventsourcing.*
import io.github.uharaqo.es.proto.example.*
import doobie.util.transactor.Transactor
import io.grpc.{Metadata, Status}
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

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

  private val eventRepo = DoobieEventRepository(xa)
  private val cacheFactory =
    new CacheFactory {
      override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
        CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
    }
  private val ttlMillis = 86_400_000L
  private val env = new CommandProcessorEnv {
    override val stateLoaderFactory = defaultStateLoaderFactory
    override val eventWriter        = eventRepo.writer
  }
  private val userDeps  = new UserResource.Dependencies {}
  private val groupDeps = new GroupResource.Dependencies {}

  val defaultStateLoaderFactory = EventReaderStateLoaderFactory(eventRepo.reader)
  val localStateLoaderFactory =
    debug(
      CachedStateLoaderFactory(
        defaultStateLoaderFactory,
        ScalaCacheFactory(cacheFactory, Some(Duration(ttlMillis, TimeUnit.MILLISECONDS)))
      )
    )
  val userStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(UserResource.stateInfo).unsafeRunSync() // blocking call during setup
  }
  val groupStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(GroupResource.stateInfo).unsafeRunSync() // blocking call during setup
  }
  private val processors = Seq(
    PartialCommandProcessor(
      CommandInputParser(UserResource.commandInfo(userDeps)),
      UserResource.stateInfo,
      userStateLoader
    ),
    PartialCommandProcessor(
      CommandInputParser(GroupResource.commandInfo(groupDeps)),
      GroupResource.stateInfo,
      groupStateLoader
    ),
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
