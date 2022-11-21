package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import cats.syntax.all.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.sql.*
import com.github.uharaqo.es.UserResource
import com.github.uharaqo.es.UserResource.*
import munit.*
import java.nio.charset.StandardCharsets

class EventSourcingSuite extends CatsEffectSuite {

  private val registerUser = classOf[RegisterUser].getCanonicalName()

  private var repo: DoobieEventRepository                                        = null
  private var userCommandHandler: CommandProcessor[User, UserCommand, UserEvent] = null

  private var commandRouter: CommandRouter = null

  override def beforeAll(): Unit = {
    val transactor = H2TransactorFactory.create()
    repo = DoobieEventRepository(transactor)
    userCommandHandler = UserResource.newUserCommandProcessor(repo.reader)

    val commandRegistry = DefaultCommandRegistry()
    commandRegistry.register(registerUser, userCommandHandler)

    commandRouter = CommandRouter(commandRegistry)
  }

  test("hello") {
    // TODO: improve routing
    // router.route(request, command)
    //   command fqcn => command obj
    //   command parent trait => handler
    //     handler.handle(state, command)
    // CommandRegistry.builder(handler)
    //   .commands(...)
    //   .build()

    val r1       = ResourceId("user", "i1")
    val c1       = """{"name": "Alice"}"""
    val request  = CommandRequest(r1, registerUser, c1)
    val response = CommandResponse(r1, Seq(VersionedEvent(1, "e1".getBytes(StandardCharsets.UTF_8))))

    val io = for {
      _ <- repo.initTables()
      //
      c <- commandRouter(request)
      _ <- IO.println(c)
      // repo
      b <- repo.writer(response)
      _ <- IO.println(b)
      // c <- save(response.copy(events = response.events ++ Seq(VersionedEvent(2, "e2"))))
      l <- repo.reader(response.id).compile.toVector
      _ <- IO.println(l)
      //
    } yield l

    io.map { v =>
      assertEquals(v.map(_.version), Vector(1L))
      assertEquals(v.map(b => String(b.event, StandardCharsets.UTF_8)), Vector("e1"))
    }
  }
}
