package io.github.uharaqo.es

import cats.effect.IO

import cats.implicits.*

def debug[C, S, E](commandHandler: CommandHandler[C, S, E]): CommandHandler[C, S, E] =
  (c, s, ctx) =>
    for
      _ <- IO.println(s"  Command: $c")
      r <- commandHandler(c, s, ctx)
      _ <- IO.println(s"  Response: $r")
    yield r

def debug(stateLoaderFactory: StateLoaderFactory): StateLoaderFactory =
  new StateLoaderFactory {
    override def apply[S, E](info: StateInfo[S, E]): IO[StateLoader[S]] =
      for stateLoader <- stateLoaderFactory(info)
      yield new StateLoader[S] {
        override def load(id: AggId, prevState: Option[VersionedState[S]]): IO[VersionedState[S]] =
          for
            s <- stateLoader.load(id, prevState)
            _ <- IO.println(s"  State: $s")
          yield s
      }
  }
