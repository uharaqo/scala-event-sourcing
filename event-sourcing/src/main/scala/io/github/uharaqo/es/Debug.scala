package io.github.uharaqo.es

import cats.effect.IO

import cats.implicits.*
extension (repo: EventRepository) {
  def dump[E](aggInfo: AggInfo, eventDeserializer: Deserializer[E]) =
    repo
      .reader(aggInfo, 0)
      .through(_.map(ve => eventDeserializer(ve.event).map(v => println(s"${ve.version}: $v"))))
      .compile
      .toList
      .flatMap(_.sequence)
}

def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
  (s, c, ctx) =>
    for
      _ <- IO.println(s"  Command: $c")
      r <- commandHandler(s, c, ctx)
      _ <- IO.println(s"  Response: $r")
    yield r

def debug(stateProviderFactory: StateProviderFactory): StateProviderFactory =
  new StateProviderFactory {
    override def apply[S, E](info: StateInfo[S, E]): StateProvider[S] = {
      val stateProvider = stateProviderFactory(info)
      { (id, prev) =>
        for
          s <- stateProvider.load(id, prev)
          _ <- IO.println(s"  State: $s")
        yield s
      }
    }
  }
