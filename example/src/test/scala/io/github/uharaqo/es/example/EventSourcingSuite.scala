package io.github.uharaqo.es.example

import cats.effect.{ExitCode, IO, Resource}
import cats.implicits.*
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.uharaqo.es.*
import io.github.uharaqo.es.grpc.server.GrpcAggregateInfo
import io.github.uharaqo.es.impl.repository.*
import io.github.uharaqo.es.proto.example.*
import doobie.util.transactor.Transactor
import munit.*
import scalacache.AbstractCache
import scalacache.caffeine.CaffeineCache
import scalapb.GeneratedMessage

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import io.github.uharaqo.es.proto.example.UserCommand
import io.github.uharaqo.es.proto.eventsourcing.SendCommandRequest
import io.github.uharaqo.es.proto.example.*
import scalapb.GeneratedMessage
import java.util.concurrent.ConcurrentHashMap
import io.github.uharaqo.es.proto.example.UserEvent.Empty
import java.nio.charset.StandardCharsets
import com.google.protobuf.ByteString

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
//      xa <- PostgresTransactorFactory.create()
      _ <- Resource.eval(DoobieEventRepository(xa).initTables())

      _ <- task(xa)
    yield ())
      .use(_ => IO(ExitCode.Success))
}

class UserResourceSetup(xa: Transactor[IO]) {
  import UserResource.*

  val eventRepo = DoobieEventRepository(xa)
  val stateProviderFactory =
    debug(DefaultStateProviderFactory(eventRepo.reader, EventSourcingSuite.cacheFactory, 86_400_000L))
  val dep  = new Dependencies {}
  val proc = CommandProcessor(info.commandRegistry(dep), stateProviderFactory, eventRepo.writer)

  val projection =
    ScheduledProjection(
      ProjectionProcessor(
        info.eventCodec.deserializer,
        r => IO.println(s"--- ${info.stateInfo.name}, $r ---").map(_ => r.asRight),
        2,
        1 seconds
      ),
      ProjectionEvent("", 0L, 0, null),
      prev => EventQuery(info.stateInfo.name, prev.timestamp),
      eventRepo,
      100 millis,
      100 millis,
    )

  val tester = info.newTester(proc, stateProviderFactory)
}

class GroupResourceSetup(xa: Transactor[IO]) {
  import GroupResource.*

  val eventRepo = DoobieEventRepository(xa)
  val stateProviderFactory =
    debug(DefaultStateProviderFactory(eventRepo.reader, EventSourcingSuite.cacheFactory, 86_400_000L))
  val dep  = new Dependencies {}
  val proc = CommandProcessor(info.commandRegistry(dep), stateProviderFactory, eventRepo.writer)

  val tester = info.newTester(proc, stateProviderFactory)
}
