package com.github.uharaqo.es.example

import cats.effect.{ExitCode, IO, Resource}
import cats.implicits.*
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.uharaqo.es.*
import com.github.uharaqo.es.grpc.server.GrpcAggregateInfo
import com.github.uharaqo.es.impl.repository.*
import com.github.uharaqo.es.proto.example.*
import doobie.util.transactor.Transactor
import munit.*
import scalacache.AbstractCache
import scalacache.caffeine.CaffeineCache
import scalapb.GeneratedMessage

import java.time.Instant
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.concurrent.duration.*

class EventSourcingSuite extends CatsEffectSuite {

  private val user1 = "user1"
  private val user2 = "user2"

  test("user aggregate") {
    val test1 = { (xa: Transactor[IO]) =>
      import UserResource.*

      val eventRepo = DoobieEventRepository(xa)
      val stateProviderFactory =
        debug(DefaultStateProviderFactory(eventRepo.reader, EventSourcingSuite.cacheFactory, 86_400_000L))
      val dep  = new Dependencies {}
      val proc = CommandProcessor(info.commandRegistry(dep), stateProviderFactory, eventRepo.writer)

      val projection =
        ScheduledProjection(
          ProjectionProcessor(
            info.eventDeserializer,
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
      import tester.*

      for {
        _ <- projection.start
        _ <- Resource.eval(IO.sleep(1 seconds))
        _ <- Resource.eval(for {
          _ <- sendPb(user1, RegisterUser("Alice"))
            .eventsPb(UserRegistered("Alice"))
            .states((user1, User("Alice", 0)))
          _ <- IO.sleep(100 millis)

          _ <- sendPb(user1, RegisterUser("Alice"))
            .failsBecause("Already registered")
          _ <- IO.sleep(100 millis)

          _ <- sendPb(user1, AddPoint(30))
            .eventsPb(PointAdded(30))
            .states((user1, User("Alice", 30)))
          _ <- IO.sleep(100 millis)

          _ <- sendPb(user1, AddPoint(80))
            .eventsPb(PointAdded(80))
            .states((user1, User("Alice", 110)))
          _ <- IO.sleep(100 millis)

          _ <- sendPb(user2, RegisterUser("Bob"))
            .eventsPb(UserRegistered("Bob"))
            .states((user2, User("Bob", 0)))
          _ <- IO.sleep(100 millis)

          _ <- sendPb(user1, SendPoint(user2, 9999))
            .failsBecause("Point Shortage")
          _ <- IO.sleep(100 millis)

          _ <- sendPb(user1, SendPoint("INVALID_USER", 10))
            .failsBecause("User not found")
          _ <- IO.sleep(100 millis)

          _ <- sendPb(user1, SendPoint(user2, 10))
            .eventsPb(
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
      import GroupResource.*

      val eventRepo = DoobieEventRepository(xa)
      val stateProviderFactory =
        debug(DefaultStateProviderFactory(eventRepo.reader, EventSourcingSuite.cacheFactory, 86_400_000L))
      val dep  = new Dependencies {}
      val proc = CommandProcessor(info.commandRegistry(dep), stateProviderFactory, eventRepo.writer)

      val tester = info.newTester(proc, stateProviderFactory)
      import tester.*

      val id1   = "g1"
      val name1 = "name1"

      implicit val eMapper = (es: GroupEvent) => es.asMessage
      implicit val cMapper = (cs: GroupCommand) => cs.asMessage
      Resource.eval(
        for
          _ <- sendPb(id1, CreateGroup("INVALID_USER", name1))
            .failsBecause("User not found")

          _ <- sendPb(id1, CreateGroup(user1, name1))
            .eventsPb(GroupCreated(user1, name1))
            .states((id1, Group(user1, name1, Set(user1))))

          _ <- sendPb(id1, CreateGroup(user1, name1))
            .failsBecause("Already exists")

          _ <- sendPb(id1, AddUser(user2))
            .eventsPb(UserAdded(user2))
            .states((id1, Group(user1, name1, Set(user1, user2))))

          _ <- sendPb(id1, AddUser("INVALID_USER"))
            .failsBecause("User not found")
        yield ()
      )
    }

    EventSourcingSuite.run(xa => test1(xa) >> test2(xa))
  }
}

object EventSourcingSuite {
  private val transactor =
    H2TransactorFactory.create()
    // PostgresTransactorFactory.create()

  val cacheFactory = new CacheFactory {
    override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
      CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
  }

  def run(task: Transactor[IO] => Resource[IO, Unit]) =
    (for
      xa <- transactor
      _  <- Resource.eval(DoobieEventRepository(xa).initTables())

      _ <- task(xa)
      _ <- Resource.eval(IO.sleep(3 seconds))
    yield ())
      .use(_ => IO(ExitCode.Success))
}

extension [S, C <: GeneratedMessage, E <: GeneratedMessage, D](info: GrpcAggregateInfo[S, C, E, D]) {
  def newTester(
    processor: CommandProcessor,
    stateProviderFactory: StateProviderFactory
  ): CommandTester[S, C, E] =
    CommandTester(info.stateInfo, processor, stateProviderFactory)
}
