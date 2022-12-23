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
