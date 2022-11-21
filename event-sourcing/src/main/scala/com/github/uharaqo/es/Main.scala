package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.eventsourcing.UserResource
import com.github.uharaqo.es.eventsourcing.UserResource.*
import com.github.uharaqo.es.io.sql.*

object Main extends IOApp {

  import cats.effect.unsafe.implicits.global

  private val commandRegistry = DefaultCommandRegistry()
  private val commandRouter   = CommandRouter(commandRegistry)
  private val repo            = DoobieEventRepository(H2TransactorFactory.create())

  override def run(args: List[String]): IO[ExitCode] = {
    // TODO: improve routing
    // router.route(request, command)
    //   command fqcn => command obj
    //   command parent trait => handler
    //     handler.handle(state, command)
    // CommandRegistry.builder(handler)
    //   .commands(...)
    //   .build()

    val registerUser       = classOf[RegisterUser].getCanonicalName()
    val userCommandHandler = UserResource.newUserCommandProcessor(repo.reader)
    commandRegistry.register(registerUser, userCommandHandler)

    val r1       = ResourceId("user", "i1")
    val c1       = """{"name": "Alice"}"""
    val request  = CommandRequest(r1, registerUser, c1)
    val response = CommandResponse(r1, Seq(VersionedEvent(1, "e1")))

    for {
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
    } yield ExitCode.Success
  }
}
