package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import cats.syntax.all.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.sql.*
import munit.*

class EventSourcingSuite extends CatsEffectSuite {

  private val transactor                  = H2TransactorFactory.create()
  private val repo: DoobieEventRepository = DoobieEventRepository(transactor)

  private val dispatcher =
    CommandDispatcher(
      UserResource.newCommandRegistry(repo) ++
        GroupResource.newCommandRegistry(repo)
    )
  private val stateProvider = StateProvider(repo.reader)

  private val user1 = "user1"
  private val user2 = "user2"

  test("user resource") {
    val test1 = {
      import com.github.uharaqo.es.UserResource.*

      val tester: CommandTester[User, UserCommand, UserEvent] =
        CommandTester(info, commandSerializer, dispatcher, stateProvider)
      import tester.*

      for
        _ <- send(user1, RegisterUser("Alice"))
          .events(UserRegistered("Alice"))
          .states((user1, User("Alice", 0)))

        _ <- send(user1, RegisterUser("Alice"))
          .failsBecause("Already registered")

        _ <- send(user1, AddPoint(30))
          .events(PointAdded(30))
          .states((user1, User("Alice", 30)))

        _ <- send(user1, AddPoint(80))
          .events(PointAdded(80))
          .states((user1, User("Alice", 110)))

        _ <- send(user2, RegisterUser("Bob"))
          .events(UserRegistered("Bob"))
          .states((user2, User("Bob", 0)))

        _ <- send(user1, SendPoint(user2, 9999))
          .failsBecause("Point Shortage")

        _ <- send(user1, SendPoint("INVALID_USER", 10))
          .failsBecause("User not found")

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

    val test2 = {
      import com.github.uharaqo.es.GroupResource.*
      val tester: CommandTester[Group, GroupCommand, GroupEvent] =
        CommandTester(info, commandSerializer, dispatcher, stateProvider)
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

    for
      _ <- repo.initTables() // init DB
      _ <- test1
      _ <- test2
    yield ()
  }
}
