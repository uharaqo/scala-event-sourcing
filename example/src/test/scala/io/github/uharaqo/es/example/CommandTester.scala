package io.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import io.github.uharaqo.es.*
import munit.Assertions.*
import scalapb.GeneratedMessage

import java.nio.charset.StandardCharsets.UTF_8

class CommandTester[S, C, E](
  info: StateInfo[S, E],
  commandInputFactory: (AggId, C) => IO[CommandInput],
  processor: CommandProcessor,
  stateLoaderFactory: StateLoaderFactory,
) {

  def command(aggId: AggId, c: C): IO[EventRecords] =
    commandInputFactory(aggId, c) >>= command

  def command[CS](aggId: AggId, c: CS)(implicit mapper: CS => C): IO[EventRecords] =
    command(aggId, mapper(c))

  def command(input: CommandInput): IO[EventRecords] =
    processor(input).map(_.records)

  extension (io: IO[EventRecords]) {
    def events(events: E*): IO[EventRecords] =
      validateEvents(events)

    def events[ES](events: ES*)(implicit mapper: ES => E): IO[EventRecords] =
      validateEvents(events.map(mapper))

    private def validateEvents(events: Seq[E]) =
      for
        v  <- io
        es <- v.traverse(r => info.eventCodec.convert(r.event))
        _ = assertEquals(es, events)
      yield v

    def states(states: (AggId, S)*) =
      for
        v           <- io
        stateLoader <- stateLoaderFactory(info)
        ss          <- states.traverse(e => stateLoader.load(e._1))
        _ = assertEquals(ss.map(_.state), states.map(_._2))
      yield v

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
