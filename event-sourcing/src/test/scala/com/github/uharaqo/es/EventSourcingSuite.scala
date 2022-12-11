package com.github.uharaqo.es

import cats.effect.{ExitCode, IO, Resource}
import cats.implicits.*
import fs2.Stream
import munit.*
import scalacache.AbstractCache
import scalacache.caffeine.CaffeineCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.uharaqo.es.impl.repository.*
import doobie.util.transactor.Transactor

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class EventSourcingSuite extends CatsEffectSuite {

  private val transactor = H2TransactorFactory.create()
//  private val transactor = PostgresTransactorFactory.create()
  private val repository = (xa: Transactor[IO]) => DoobieEventRepository(xa)

  private val stateProvider = (reader: EventReader) =>
    debug(
      CachedStateProviderFactory(
        EventReaderStateProviderFactory(reader),
        ScalaCacheFactory(
          new CacheFactory {
            override def create[S, E](info: StateInfo[S, E]): AbstractCache[IO, AggId, VersionedState[S]] =
              CaffeineCache(Caffeine.newBuilder().maximumSize(10000L).build)
          },
          Some(Duration(86400, TimeUnit.SECONDS))
        )
      ).memoise
    )
  private val dispatcher = (repo: EventRepository) =>
    CommandProcessor(
      UserResource.newCommandRegistry()
        ++ GroupResource.newCommandRegistry(),
      stateProvider(repo.reader),
      repo.writer,
    )

  def newTester[S, C, E](info: StateInfo[S, E], commandSerializer: Serializer[C], repo: EventRepository) =
    CommandTester(info, commandSerializer, dispatcher(repo), stateProvider(repo.reader))

  private val user1 = "user1"
  private val user2 = "user2"

  test("user aggregate") {
    val test1 = { (repo: EventRepository) =>
      import com.github.uharaqo.es.example.proto.user.*
      import com.github.uharaqo.es.UserResource.*
      val tester = newTester(info, commandSerializer, repo)
      import tester.*

      for
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
            PointReceived(user1, 10)
          )
          .states(
            (user1, User("Alice", 100)),
            (user2, User("Bob", 10))
          )
      yield ()
    }

    val test2 = { (repo: EventRepository) =>
      import com.github.uharaqo.es.example.proto.group.*
      import com.github.uharaqo.es.GroupResource.*
      val tester = newTester(info, commandSerializer, repo)
      import tester.*

      val id1   = "g1"
      val name1 = "name1"

      for
        _ <- send(id1, CreateGroup("INVALID_USER", name1))
          .failsBecause("User not found")

        _ <- send(id1, CreateGroup(user1, name1))
          .events(GroupCreated(user1, name1))
          .states((id1, Group(user1, name1, Set(user1))))

        _ <- send(id1, CreateGroup(user1, name1))
          .failsBecause("Already exists")

        _ <- send(id1, AddUser(user2))
          .events(UserAdded(user2))
          .states((id1, Group(user1, name1, Set(user1, user2))))

        _ <- send(id1, AddUser("INVALID_USER"))
          .failsBecause("User not found")
      yield ()
    }

    val projection = { (repo: ProjectionRepository) =>
      ScheduledProjection(
        ProjectionProcessor(
          UserResource.info.eventDeserializer,
          r => IO.println(s"--- ${UserResource.info.name}, $r ---").map(_ => r.asRight),
          2,
          1 seconds
        ),
        ProjectionEvent("", 0L, 0, null),
        prev => EventQuery(UserResource.info.name, prev.timestamp),
        repo,
        100 millis,
        100 millis,
      )
    }

    (for
      xa <- transactor
      repo = repository(xa)
      _ <- Resource.eval(repo.initTables())
      _ <- projection(repo).start
      _ <- Resource.eval(test1(repo) >> test2(repo))
      _ <- Resource.eval(IO.sleep(3 seconds))
    yield ())
      .use(_ => IO(ExitCode.Success))
  }
}
