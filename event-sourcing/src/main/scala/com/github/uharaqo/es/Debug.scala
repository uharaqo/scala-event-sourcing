package com.github.uharaqo.es

import cats.effect.IO

def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
  (s, c, ctx) =>
    for
      _ <- IO.println(s"  Command: $c")
      r <- commandHandler(s, c, ctx)
      _ <- IO.println(s"  Response: $r")
    yield r

def debug(stateProviderFactory: StateProviderFactory): StateProviderFactory =
  new StateProviderFactory {
    override def create[S, E](info: StateInfo[S, E]): StateProvider[S] = {
      val stateProvider = stateProviderFactory.create(info)
      { (id, prev) =>
        for
          s <- stateProvider.load(id, prev)
          _ <- IO.println(s"  State: $s")
        yield s
      }
    }
  }
