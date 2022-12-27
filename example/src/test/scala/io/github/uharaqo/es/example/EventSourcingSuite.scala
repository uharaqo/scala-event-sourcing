package io.github.uharaqo.es.example

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.implicits.*
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.ByteString
import doobie.util.transactor.Transactor
import io.github.uharaqo.es.*
import io.github.uharaqo.es.repository.*
import io.github.uharaqo.es.grpc.proto.SendCommandRequest
import io.github.uharaqo.es.example.proto.*
import munit.*
import scalacache.AbstractCache
import scalacache.caffeine.CaffeineCache
import scalapb.GeneratedMessage

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class EventSourcingSuite extends CatsEffectSuite {

  private val user1  = "user1"
  private val user2  = "user2"
  private val group1 = "g1"
  private val name1  = "name1"

  test("user aggregate") {

    val test1 = { (setup: TestSetup) =>
      val userAggregateSetup  = new UserAggregateSetup(setup)
      val groupAggregateSetup = new GroupAggregateSetup(setup)
      val processors          = Seq(userAggregateSetup.commandProcessor, groupAggregateSetup.commandProcessor)

      val userTester  = setup.newTester(UserAggregate.stateInfo, UserAggregate.commandMapper, processors)
      val groupTester = setup.newTester(GroupAggregate.stateInfo, GroupAggregate.commandMapper, processors)

      val userTest = {
        import UserAggregate.*
        import userTester.*
        for {
          _ <- command(user1, RegisterUser("Alice"))
            .events(UserRegistered("Alice"))
            .states((user1, User("Alice", 0)))
          _ <- IO.sleep(100 millis)

          _ <- command(user1, RegisterUser("Alice"))
            .failsBecause("Already registered")

          _ <- command(user1, AddPoint(30))
            .events(PointAdded(30))
            .states((user1, User("Alice", 30)))

          _ <- command(user1, AddPoint(80))
            .events(PointAdded(80))
            .states((user1, User("Alice", 110)))

          _ <- command(user2, RegisterUser("Bob"))
            .events(UserRegistered("Bob"))
            .states((user2, User("Bob", 0)))

          _ <- command(user1, SendPoint(user2, 9999))
            .failsBecause("Point Shortage")

          _ <- command(user1, SendPoint("INVALID_USER", 10))
            .failsBecause("User not found")

          _ <- command(user1, SendPoint(user2, 10))
            .events(
              PointSent(user2, 10),
              PointReceived(user1, 10),
            )
            .states(
              (user1, User("Alice", 100)),
              (user2, User("Bob", 10))
            )

          _ <- setup.dump(stateInfo, user1)
          _ <- setup.dump(stateInfo, user2)
        } yield ()
      }

      val groupTest = {
        import GroupAggregate.*
        import groupTester.*

        for
          _ <- command(group1, CreateGroup("INVALID_USER", name1))
            .failsBecause("User not found")

          _ <- command(group1, CreateGroup(user1, name1))
            .events(GroupCreated(user1, name1))
            .states((group1, Group(user1, name1, Set(user1))))

          _ <- command(group1, CreateGroup(user1, name1))
            .failsBecause("Already exists")

          _ <- command(group1, AddUser(user2))
            .events(UserAdded(user2))
            .states((group1, Group(user1, name1, Set(user1, user2))))

          _ <- command(group1, AddUser("INVALID_USER"))
            .failsBecause("User not found")

          _ <- setup.dump(stateInfo, group1)
        yield ()
      }

      for {
        _ <- userAggregateSetup.init()
        _ <- Resource.eval(IO.sleep(100 millis))
        _ <- Resource.eval(
          userTest >> groupTest
        )
      } yield ()
    }

    TestSetup.run(setup => test1(setup))
  }
}

object TestSetup {

  val cacheFactory = ScalaCacheFactory(
    new CacheFactory {
      override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
        CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
    },
    Some(Duration(86_400_000L, TimeUnit.MILLISECONDS))
  )

  def run(task: TestSetup => Resource[IO, Unit]) =
    (for
      xa <- H2TransactorFactory.create()
//      xa <- PostgresTransactorFactory.create()
      _ <- Resource.eval(DoobieEventRepository(xa).initTables())

      _ <- task(new TestSetup(xa))
    yield ())
      .use(_ => IO(ExitCode.Success))
}

class TestSetup(val xa: Transactor[IO]) {

  val env =
    new CommandProcessorEnv {
      override val eventRepository    = DoobieEventRepository(xa)
      override val stateLoaderFactory = EventReaderStateLoaderFactory(eventRepository)
    }

  def newTester[S, C <: GeneratedMessage, E <: GeneratedMessage, C2](
    stateInfo: StateInfo[S, E],
    commandMapper: C2 => C, // just to capture the type C2
    processors: Seq[PartialCommandProcessor],
  ): CommandTester[S, C, E] =
    val processor = CommandProcessor(processors)
    val commandFactory =
      (id: AggId, c: C) =>
        IO {
          val p = com.google.protobuf.any.Any.pack(c)
          CommandInput(
            info = AggInfo(stateInfo.name, id),
            name = p.typeUrl.split('/').last,
            payload = p.value.toByteArray(),
          )
        }
    CommandTester(stateInfo, commandFactory, processor, env.stateLoaderFactory)

  def dump[S, E](stateInfo: StateInfo[S, E], id: AggId): IO[Unit] =
    env.eventRepository
      .loadEvents(stateInfo, id)
      .evalMap((v, e) => IO.println(s"[${stateInfo.name}/${id}] ${v}: ${e}"))
      .compile
      .foldMonoid
}

class UserAggregateSetup(setup: TestSetup) {
  import UserAggregate.*

  val env  = setup.env
  val deps = new Dependencies {}

  val localStateLoaderFactory = debug(CachedStateLoaderFactory(env.stateLoaderFactory, TestSetup.cacheFactory))
  val localStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(stateInfo).unsafeRunSync() // blocking call during setup
  }

  val projection =
    ScheduledProjection(
      ProjectionProcessor(
        stateInfo.eventCodec,
        r => IO.println(s"--- ${stateInfo.name}, $r ---").map(_ => r.asRight),
        2,
        1 seconds
      ),
      ProjectionEvent("", 0L, 0, null),
      prev => EventQuery(stateInfo.name, prev.timestamp),
      env.eventRepository,
      10 millis,
      10 millis,
    )

  val commandProcessor =
    PartialCommandProcessor(
      stateInfo,
      commandInfo(deps),
      localStateLoader,
      env.stateLoaderFactory,
      env.eventRepository
    )

  def init(): Resource[IO, Unit] =
    for {
      _ <- projection.start
    } yield ()
}

class GroupAggregateSetup(setup: TestSetup) {
  import GroupAggregate.*

  val env  = setup.env
  val deps = new Dependencies {}

  val localStateLoaderFactory = debug(CachedStateLoaderFactory(env.stateLoaderFactory, TestSetup.cacheFactory))
  val localStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(stateInfo).unsafeRunSync() // blocking call during setup
  }

  val commandProcessor =
    PartialCommandProcessor(
      stateInfo,
      commandInfo(deps),
      localStateLoader,
      env.stateLoaderFactory,
      env.eventRepository
    )
}
