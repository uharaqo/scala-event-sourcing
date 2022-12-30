package io.github.uharaqo.es.example.grpc

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import doobie.util.transactor.Transactor
import io.github.uharaqo.es.*
import io.github.uharaqo.es.example.*
import io.github.uharaqo.es.grpc.server.GrpcServer
import io.github.uharaqo.es.repository.DoobieEventRepository
import io.github.uharaqo.es.repository.H2TransactorFactory
import io.github.uharaqo.es.grpc.proto.*
import io.grpc.Metadata
import io.grpc.Status

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

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

  private val cacheFactory =
    new CacheFactory {
      override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
        CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
    }
  private val ttlMillis = 86_400_000L
  private val env = new CommandProcessorEnv {
    override val eventRepository      = DoobieEventRepository(xa)
    override val projectionRepository = eventRepository.asInstanceOf[DoobieEventRepository]
    override val stateLoaderFactory   = EventReaderStateLoaderFactory(eventRepository)
  }
  private val userDeps  = new UserAggregate.Dependencies {}
  private val groupDeps = new GroupAggregate.Dependencies {}

  private val localStateLoaderFactory =
    debug(
      CachedStateLoaderFactory(
        env.stateLoaderFactory,
        ScalaCacheFactory(cacheFactory, Some(Duration(ttlMillis, TimeUnit.MILLISECONDS)))
      )
    )
  private val userStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(UserAggregate.stateInfo).unsafeRunSync() // blocking call during setup
  }
  private val groupStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(GroupAggregate.stateInfo).unsafeRunSync() // blocking call during setup
  }
  private val processors = Seq(
    PartialCommandProcessor(
      UserAggregate.stateInfo,
      UserAggregate.commandInfo(userDeps),
      userStateLoader,
      env.stateLoaderFactory,
      env.eventRepository,
    ),
    PartialCommandProcessor(
      GroupAggregate.stateInfo,
      GroupAggregate.commandInfo(groupDeps),
      groupStateLoader,
      env.stateLoaderFactory,
      env.eventRepository,
    ),
  )
  private val processor = CommandProcessor(processors)

  private val parser: SendCommandRequest => IO[CommandInput] = { req =>
    IO {
      val p = req.payload.get
      CommandInput(req.aggregate, req.id, p.typeUrl.split('/').last, p.value.toByteArray)
    }
      .handleErrorWith(t => IO.raiseError(Status.INVALID_ARGUMENT.withCause(t).asRuntimeException()))
  }

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
          output <- processor(parsed)
        yield SendCommandResponse(output.version.getOrElse(throw Status.UNKNOWN.asRuntimeException()), "TODO"))
          .handleErrorWith(errorHandler)

      override def loadState(request: LoadStateRequest, ctx: Metadata): IO[LoadStateResponse] = ???
    }
  )
}
