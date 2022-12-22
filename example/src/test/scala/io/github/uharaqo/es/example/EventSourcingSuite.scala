package io.github.uharaqo.es.example

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.implicits.*
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.ByteString
import doobie.util.transactor.Transactor
import io.github.uharaqo.es.*
import io.github.uharaqo.es.impl.repository.*
import io.github.uharaqo.es.proto.eventsourcing.SendCommandRequest
import io.github.uharaqo.es.proto.example.*
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
  import EventSourcingSuite.*

  private val user1 = "user1"
  private val user2 = "user2"

  test("user aggregate") {
    val test1 = { (xa: Transactor[IO]) =>
      val setup  = UserResourceSetup(xa)
      val tester = setup.tester
      import UserResource.*
      import tester.*

      for {
        _ <- setup.projection.start
        _ <- Resource.eval(IO.sleep(1 seconds))
        _ <- Resource.eval(for {
          _ <- send(user1, RegisterUser("Alice"))
            .events(UserRegistered("Alice"))
            .states((user1, User("Alice", 0)))
          _ <- IO.sleep(100 millis)

          _ <- send(user1, RegisterUser("Alice"))
            .failsBecause("Already registered")
          _ <- IO.sleep(100 millis)

          _ <- send(user1, AddPoint(30))
            .events(PointAdded(30))
            .states((user1, User("Alice", 30)))
          _ <- IO.sleep(100 millis)

          _ <- send(user1, AddPoint(80))
            .events(PointAdded(80))
            .states((user1, User("Alice", 110)))
          _ <- IO.sleep(100 millis)

          _ <- send(user2, RegisterUser("Bob"))
            .events(UserRegistered("Bob"))
            .states((user2, User("Bob", 0)))
          _ <- IO.sleep(100 millis)

          _ <- send(user1, SendPoint(user2, 9999))
            .failsBecause("Point Shortage")
          _ <- IO.sleep(100 millis)

          _ <- send(user1, SendPoint("INVALID_USER", 10))
            .failsBecause("User not found")
          _ <- IO.sleep(100 millis)

          _ <- send(user1, SendPoint(user2, 10))
            .events(
              PointSent(user2, 10),
              PointReceived(user1, 10),
            )
            .states(
              (user1, User("Alice", 100)),
              (user2, User("Bob", 10))
            )
        } yield ())
      } yield ()
    }

    val test2 = { (xa: Transactor[IO]) =>
      val group1 = "g1"
      val name1  = "name1"

      val setup  = GroupResourceSetup(xa)
      val tester = setup.tester
      import GroupResource.*
      import tester.*

      Resource.eval(
        for
          _ <- send(group1, CreateGroup("INVALID_USER", name1))
            .failsBecause("User not found")

          _ <- send(group1, CreateGroup(user1, name1))
            .events(GroupCreated(user1, name1))
            .states((group1, Group(user1, name1, Set(user1))))

          _ <- send(group1, CreateGroup(user1, name1))
            .failsBecause("Already exists")

          _ <- send(group1, AddUser(user2))
            .events(UserAdded(user2))
            .states((group1, Group(user1, name1, Set(user1, user2))))

          _ <- send(group1, AddUser("INVALID_USER"))
            .failsBecause("User not found")
        yield ()
      )
    }

    EventSourcingSuite.run(xa => test1(xa) >> test2(xa))
  }
}

object EventSourcingSuite {

  val cacheFactory = new CacheFactory {
    override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
      CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
  }

  def run(task: Transactor[IO] => Resource[IO, Unit]) =
    (for
      xa <- H2TransactorFactory.create()
      // xa <- PostgresTransactorFactory.create()
      _ <- Resource.eval(DoobieEventRepository(xa).initTables())

      _ <- task(xa)
    yield ())
      .use(_ => IO(ExitCode.Success))

  def newTester[S, C <: GeneratedMessage, E <: GeneratedMessage, D](
    stateInfo: StateInfo[S, E],
    processor: CommandProcessor,
    stateLoaderFactory: StateLoaderFactory
  ): CommandTester[S, C, E] =
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
    CommandTester(stateInfo, commandFactory, processor, stateLoaderFactory)
}

class UserResourceSetup(xa: Transactor[IO]) {
  import UserResource.*

  val ttlMillis = 86_400_000L

  val eventRepo = DoobieEventRepository(xa)
  val dep       = new Dependencies {}

  val defaultStateLoaderFactory = EventReaderStateLoaderFactory(eventRepo.reader)
  val localStateLoaderFactory =
    debug(
      CachedStateLoaderFactory(
        defaultStateLoaderFactory,
        ScalaCacheFactory(EventSourcingSuite.cacheFactory, Some(Duration(ttlMillis, TimeUnit.MILLISECONDS)))
      )
    )
  val localStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(stateInfo).unsafeRunSync() // blocking call during setup
  }

  val env = new CommandProcessorEnv {
    override val stateLoaderFactory = defaultStateLoaderFactory
    override val eventWriter        = eventRepo.writer
  }

  val inputParser = CommandInputParser(commandInfo(dep))
  val proc        = CommandProcessor(env, Seq(PartialCommandProcessor(inputParser, stateInfo, localStateLoader)))

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
      eventRepo,
      100 millis,
      100 millis,
    )

  val tester: CommandTester[User, UserCommandMessage, UserEventMessage] =
    EventSourcingSuite.newTester(stateInfo, proc, env.stateLoaderFactory)
}

class GroupResourceSetup(xa: Transactor[IO]) {
  import GroupResource.*

  val ttlMillis = 86_400_000L

  val eventRepo = DoobieEventRepository(xa)
  val dep       = new Dependencies {}

  val defaultStateLoaderFactory = EventReaderStateLoaderFactory(eventRepo.reader)
  val localStateLoaderFactory =
    debug(
      CachedStateLoaderFactory(
        defaultStateLoaderFactory,
        ScalaCacheFactory(EventSourcingSuite.cacheFactory, Some(Duration(ttlMillis, TimeUnit.MILLISECONDS)))
      )
    )
  val localStateLoader = {
    import cats.effect.unsafe.implicits.*
    localStateLoaderFactory(stateInfo).unsafeRunSync() // blocking call during setup
  }

  val env = new CommandProcessorEnv {
    override val stateLoaderFactory = defaultStateLoaderFactory
    override val eventWriter        = eventRepo.writer
  }

  val inputParser = CommandInputParser(commandInfo(dep))
  val proc        = CommandProcessor(env, Seq(PartialCommandProcessor(inputParser, stateInfo, localStateLoader)))

  val tester: CommandTester[Group, GroupCommandMessage, GroupEventMessage] =
    EventSourcingSuite.newTester(stateInfo, proc, env.stateLoaderFactory)
}
