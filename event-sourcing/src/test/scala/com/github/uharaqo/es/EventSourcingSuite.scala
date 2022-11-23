package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import cats.syntax.all.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.sql.*
import com.github.uharaqo.es.UserResource
import com.github.uharaqo.es.UserResource.*
import munit.*

class EventSourcingSuite extends CatsEffectSuite {

  private val id1 = ResourceId(UserResource.info.name, "id1")
  private val id2 = ResourceId(UserResource.info.name, "id2")

  private val transactor                    = H2TransactorFactory.create()
  private val repo: DoobieEventRepository   = DoobieEventRepository(transactor)
  private val userResource                  = UserResource(repo)
  private val processor                     = userResource.commandProcessor
  private val commandRegistry               = CommandRegistry.from(processor, commandDeserializers)
  private val dispatcher: CommandDispatcher = CommandDispatcher(commandRegistry)

  private val tester: CommandTester[User, UserCommand, UserEvent] =
    CommandTester(UserResource.info, processor, commandDeserializers, StateProvider(repo.reader))
  import tester.*

  import com.github.plokhotnyuk.jsoniter_scala.macros._
  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonCodec => _, _}
  given codec: JsonValueCodec[UserCommand] = JsonCodecMaker.make

  test("user") {
    for {
      // init DB
      _ <- repo.initTables()

      _ <- send(id1, RegisterUser("Alice"))
        .events(UserRegistered("Alice"))
        .states((id1.id, User("Alice", 0)))

      _ <- send(id1, RegisterUser("Alice"))
        .failsBecause("Already registered")

      _ <- send(id1, AddPoint(30))
        .events(PointAdded(30))
        .states((id1.id, User("Alice", 30)))

      _ <- send(id1, AddPoint(80))
        .events(PointAdded(80))
        .states((id1.id, User("Alice", 110)))

      _ <- send(id2, RegisterUser("Bob"))
        .events(UserRegistered("Bob"))
        .states((id2.id, User("Bob", 0)))

      _ <- send(id1, SendPoint(id2.id, 9999))
        .failsBecause("Point Shortage")

      _ <- send(id1, SendPoint(id2.id, 10))
        .events(
          PointSent(id2.id, 10),
          PointReceived(id1.id, 10)
        )
        .states(
          (id1.id, User("Alice", 100)),
          (id2.id, User("Bob", 10))
        )
    } yield ()
  }
}
