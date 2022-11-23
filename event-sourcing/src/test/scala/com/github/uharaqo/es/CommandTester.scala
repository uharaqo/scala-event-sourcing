package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import cats.syntax.all.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.CommandRequest
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import munit.Assertions.*

import java.nio.charset.StandardCharsets.UTF_8

class CommandTester[S, C, E](
  private val info: ResourceInfo[S, E],
  processor: CommandProcessor[S, C, E],
  commandDeserializers: Map[Fqcn, CommandDeserializer[C]],
  private val stateProvider: StateProvider,
) {
  private val commandRegistry = CommandRegistry.from(processor, commandDeserializers)
  private val dispatcher      = CommandDispatcher(commandRegistry)

  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonCodec => _, _}
  def send(resourceId: ResourceId, command: C)(using codec: JsonValueCodec[C]): IO[Seq[CommandResponse]] =
    send(
      CommandRequest(
        resourceId,
        command.getClass().getCanonicalName(),
        String(writeToArray(command), UTF_8)
      )
    )

  def send(request: CommandRequest): IO[Seq[CommandResponse]] = {
    import unsafe.implicits.*
    dispatcher(request)
  }

  extension (io: IO[Seq[CommandResponse]]) {
    def events(events: E*): IO[Seq[CommandResponse]] = io flatMap { v =>
      for {
        es <- v.map(r => info.eventDeserializer(r.event.event)).traverse(x => x)
        _ = assertEquals(es, events)
      } yield v
    }

    def states(states: (ResourceIdentifier, S)*) = io flatMap { v =>
      for {
        ss <- states.map(e => stateProvider.get(info, e._1)).traverse(x => x)
        _ = assertEquals(ss.map(_.state), states.map(_._2))
      } yield v
    }

    def failsBecause(message: String): IO[Unit] = {
      import unsafe.implicits.*
      val e = intercept[EsException.CommandHandlerFailure](io.unsafeRunSync())
      assertEquals(e.getCause().getMessage(), message)
      IO.unit
    }
  }
}
