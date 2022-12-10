package com.github.uharaqo.es

import cats.effect.IO

class DefaultCommandHandlerContext[S, E](
  override val info: StateInfo[S, E],
  override val id: AggId,
  override val prevVer: Version,
  private val stateProvider: StateProvider,
) extends CommandHandlerContext[S, E] {

  import cats.implicits.*

  override def save(events: E*): IO[Seq[EventRecord]] =
    events.zipWithIndex.traverse {
      case (e, i) =>
        info.eventSerializer(e).map { e =>
          EventRecord(info.name, id, prevVer + i + 1, System.currentTimeMillis(), e)
        }
    }

  override def withState[S2, E2](info: StateInfo[S2, E2], id: AggId)(
    handler: (S2, CommandHandlerContext[S2, E2]) => IO[Seq[EventRecord]]
  ): IO[Seq[EventRecord]] =
    for
      verS <- stateProvider.load(info, id)
      ctx = new DefaultCommandHandlerContext[S2, E2](info, id, verS.version, stateProvider)
      ress <- handler(verS.state, ctx)
    yield ress
}

// debugger TODO: improve these
def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
  (s, c, ctx) =>
    for
      _ <- IO.println(s"Command: $c")
      r <- commandHandler(s, c, ctx)
      _ <- IO.println(s"Response: $r")
    yield r

def debug(stateProvider: StateProvider): StateProvider =
  new StateProvider {
    override def load[S, E](info: StateInfo[S, E], id: AggId): IO[VersionedState[S]] =
      for
        s <- stateProvider.load(info, id)
        _ <- IO.println(s"State: $s")
      yield s
  }
