package io.github.uharaqo.es.example

import cats.effect.IO
import io.github.uharaqo.es.*
import munit.Assertions.*

import java.nio.charset.StandardCharsets.UTF_8

class CommandTester[C, S, E](
  info: StateInfo[S, E],
  commandInputFactory: (AggId, C) => IO[CommandInput],
  processor: CommandProcessor,
  stateLoaderFactory: StateLoaderFactory,
) {
  import cats.implicits.*

  def command[CS](id: AggId, c: CS)(implicit mapper: CS => C): IO[CommandOutput] =
    command(id, mapper(c))

  def command(id: AggId, c: C): IO[CommandOutput] =
    commandInputFactory(id, c) >>= command

  def command(input: CommandInput): IO[CommandOutput] =
    processor(input)

  extension (output: IO[CommandOutput]) {
    def events(events: E*): IO[CommandOutput] =
      validateEvents(events)

    def events[ES](events: ES*)(implicit mapper: ES => E): IO[CommandOutput] =
      validateEvents(events.map(mapper))

    private def validateEvents(events: Seq[E]) =
      for
        v  <- output
        es <- v.events.traverse(r => info.eventCodec.convert(r.event))
        _ = assertEquals(es, events)
      yield v

    def states(states: (AggId, S)*): IO[CommandOutput] =
      for
        v           <- output
        stateLoader <- stateLoaderFactory(info)
        ss          <- states.traverse(e => stateLoader.load(e._1))
        _ = assertEquals(ss.map(_.state), states.map(_._2))
      yield v

    def failsBecause(message: String): IO[Unit] =
      output.attempt.map {
        case Left(t) =>
          val e = intercept[EsException.CommandHandlerFailure](throw t)
          assertEquals(e.getCause.getMessage, message)
        case _ =>
          intercept[EsException.CommandHandlerFailure](())
      }
  }
}
