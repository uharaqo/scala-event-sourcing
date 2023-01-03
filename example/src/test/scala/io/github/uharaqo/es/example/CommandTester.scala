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

  def command[CS](id: AggId, c: CS)(using mapper: CS => C): CommandValidator[C, S, E] =
    command(id, mapper(c))

  def command(id: AggId, c: C): CommandValidator[C, S, E] =
    CommandValidator(commandInputFactory(id, c) >>= process, info, stateLoaderFactory)

  private def process(input: CommandInput): IO[CommandOutput] =
    processor(input)
}

class CommandValidator[C, S, E](
  output: IO[CommandOutput],
  stateInfo: StateInfo[S, E],
  stateLoaderFactory: StateLoaderFactory
) {
  import cats.implicits.*

  def events(events: E*): CommandValidator[C, S, E] =
    validateEvents(events)

  def events[ES](events: ES*)(using mapper: ES => E): CommandValidator[C, S, E] =
    validateEvents(events.map(mapper))

  private def validateEvents(events: Seq[E]): CommandValidator[C, S, E] =
    CommandValidator(
      for
        v  <- output
        es <- v.events.traverse(r => stateInfo.eventCodec.convert(r.event))
        _ = assertEquals(es, events)
      yield v,
      stateInfo,
      stateLoaderFactory
    )

  def states(states: (AggId, S)*) =
    for
      v           <- output
      stateLoader <- stateLoaderFactory(stateInfo)
      ss          <- states.traverse(e => stateLoader.load(e._1))
      _ = assertEquals(ss.map(_.state), states.map(_._2))
    yield v

  def failsBecause(message: String) =
    output.attempt.map {
      case Left(t) =>
        val e = intercept[EsException.CommandHandlerFailure](throw t)
        assertEquals(e.getCause().getMessage(), message)
      case _ =>
        intercept[EsException.CommandHandlerFailure](())
    }
}
