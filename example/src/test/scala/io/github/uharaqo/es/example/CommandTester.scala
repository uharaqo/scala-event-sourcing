package io.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.github.uharaqo.es.*
import munit.Assertions.*
import scalapb.GeneratedMessage

import java.nio.charset.StandardCharsets.UTF_8

class CommandTester[S, C, E](
  info: StateInfo[S, E],
  commandInputFactory: (AggId, C) => IO[CommandInput],
  dispatcher: CommandProcessor,
  stateProviderFactory: StateProviderFactory,
) {
  private val stateProvider = stateProviderFactory(info)

  def send(aggId: AggId, command: C): IO[EventRecords] =
    commandInputFactory(aggId, command) >>= send

  def send[CS](aggId: AggId, command: CS)(implicit mapper: CS => C): IO[EventRecords] =
    send(aggId, mapper(command))

  def send(input: CommandInput): IO[EventRecords] = {
    import unsafe.implicits.*
    dispatcher(input)
  }

  extension (io: IO[EventRecords]) {
    def events(events: E*): IO[EventRecords] =
      validateEvents(events)

    def events[ES](events: ES*)(implicit mapper: ES => E): IO[EventRecords] =
      validateEvents(events.map(mapper))

    private def validateEvents(events: Seq[E]) = io >>= { v =>
      for es <- v.traverse(r => info.eventCodec(r.event)) yield {
        assertEquals(es, events)
        v
      }
    }

    def states(states: (AggId, S)*) = io >>= { v =>
      for ss <- states.traverse(e => stateProvider.load(e._1)) yield {
        assertEquals(ss.map(_.state), states.map(_._2))
        v
      }
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

extension [S, C <: GeneratedMessage, E <: GeneratedMessage, D](info: AggregateInfo[S, C, E, D]) {
  def newTester(
    processor: CommandProcessor,
    stateProviderFactory: StateProviderFactory
  ): CommandTester[S, C, E] =
    val commandFactory =
      (id: AggId, c: C) =>
        IO {
          val p = com.google.protobuf.any.Any.pack(c)
          CommandInput(
            info = AggInfo(info.stateInfo.name, id),
            name = p.typeUrl.split('/').last,
            payload = p.value.toByteArray(),
          )
        }
    CommandTester(info.stateInfo, commandFactory, processor, stateProviderFactory)
}
