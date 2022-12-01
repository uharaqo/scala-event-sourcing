package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.CommandRequest
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import munit.Assertions.*

import java.nio.charset.StandardCharsets.UTF_8
import com.github.plokhotnyuk.jsoniter_scala.core.*

class CommandTester[S, C, E](
  private val info: ResourceInfo[S, E],
  commandSerializer: JsonValueCodec[C],
  private val dispatcher: CommandDispatcher,
  private val stateProvider: StateProvider,
) {

  def send(resourceId: ResourceIdentifier, command: C): IO[Seq[CommandResponse]] =
    send(
      CommandRequest(
        ResourceId(info.name, resourceId),
        command.getClass().getCanonicalName(),
        writeToArray(command)(commandSerializer),
      )
    )

  def send(request: CommandRequest): IO[Seq[CommandResponse]] = {
    import unsafe.implicits.*
    dispatcher(request)
  }

  extension (io: IO[Seq[CommandResponse]]) {
    def events(events: E*): IO[Seq[CommandResponse]] = io flatMap { v =>
      for es <- v.traverse(r => info.eventDeserializer(r.event.event))
      yield
        assertEquals(es, events)
        v
    }

    def states(states: (ResourceIdentifier, S)*) = io flatMap { v =>
      for ss <- states.traverse(e => stateProvider.get(info, e._1))
      yield
        assertEquals(ss.map(_.state), states.map(_._2))
        v
    }

    def failsBecause(message: String): IO[Unit] =
      io.attempt.map {
        case Left(t) =>
          val e = intercept[EsException.CommandHandlerFailure](throw t)
          assertEquals(e.getCause().getMessage(), message)
        case _ =>
          intercept[EsException.CommandHandlerFailure](())
      }
  }
}
