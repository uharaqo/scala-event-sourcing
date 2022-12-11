package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import munit.Assertions.*

import java.nio.charset.StandardCharsets.UTF_8

class CommandTester[S, C, E](
  info: StateInfo[S, E],
  commandSerializer: Serializer[C],
  dispatcher: CommandProcessor,
  stateProviderFactory: StateProviderFactory,
) {
  private val stateProvider = stateProviderFactory.create(info)

  def send(aggId: AggId, command: C): IO[Seq[EventRecord]] =
    import cats.effect.unsafe.implicits.global
    send(
      CommandRequest(
        AggInfo(info.name, aggId),
        command.getClass().getCanonicalName(),
        commandSerializer(command).unsafeRunSync(),
      )
    )

  def send(request: CommandRequest): IO[Seq[EventRecord]] = {
    import unsafe.implicits.*
    dispatcher(request)
  }

  extension (io: IO[Seq[EventRecord]]) {
    def events(events: E*): IO[Seq[EventRecord]] = io flatMap { v =>
      for es <- v.traverse(r => info.eventDeserializer(r.event))
      yield
        assertEquals(es, events)
        v
    }

    def states(states: (AggId, S)*) = io flatMap { v =>
      for ss <- states.traverse(e => stateProvider.load(e._1))
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

def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
  (s, c, ctx) =>
    for
      _ <- IO.println(s"Command: $c")
      r <- commandHandler(s, c, ctx)
      _ <- IO.println(s"Response: $r")
    yield r

def debug(stateProviderFactory: StateProviderFactory): StateProviderFactory =
  new StateProviderFactory {
    override def create[S, E](info: StateInfo[S, E]): StateProvider[S] = {
      val stateProvider = stateProviderFactory.create(info)
      { (id, prev) =>
        for
          s <- stateProvider.load(id, prev)
          _ <- IO.println(s"State: $s")
        yield s
      }
    }
  }
