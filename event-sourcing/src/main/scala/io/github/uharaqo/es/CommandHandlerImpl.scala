package io.github.uharaqo.es

import cats.effect.IO

class DefaultCommandHandlerContext[S, E](
  override val info: StateInfo[S, E],
  override val id: AggId,
  override val prevState: VersionedState[S],
  stateLoaderFactory: StateLoaderFactory,
) extends CommandHandlerContext[S, E] {
  import cats.implicits.*

  override def save(events: E*): IO[EventRecords] =
    events.zipWithIndex.traverse {
      case (e, i) =>
        info.eventCodec.convert(e).map { e =>
          EventRecord(info.name, id, prevState.version + i + 1, System.currentTimeMillis(), e)
        }
    }

  override def withState[S2, E2](info: StateInfo[S2, E2], id: AggId)(
    handler: (S2, CommandHandlerContext[S2, E2]) => IO[EventRecords]
  ): IO[EventRecords] =
    for
      stateLoader <- stateLoaderFactory(info)
      verS        <- stateLoader.load(id)
      ctx = new DefaultCommandHandlerContext[S2, E2](info, id, verS, stateLoaderFactory)
      ress <- handler(verS.state, ctx)
    yield ress
}

type PartialCommandHandler[S, C, E] = (S, CommandHandlerContext[S, E]) => PartialFunction[C, IO[EventRecords]]

object PartialCommandHandler {
  def toCommandHandler[S, C, E](
    handlers: Seq[PartialCommandHandler[S, C, E]]
  ): CommandHandler[S, C, E] = { (s, c, ctx) =>
    val f: PartialFunction[C, IO[EventRecords]] =
      handlers
        .map(_(s, ctx))
        .foldLeft(PartialFunction.empty)((pf1, pf2) => if pf2.isDefinedAt(c) then pf1.orElse(pf2) else pf1)

    if f.isDefinedAt(c) then f(c) else IO.raiseError(EsException.InvalidCommand(ctx.info.name))
  }

  def toCommandHandler[S, C, E, C2](
    handlers: Seq[PartialCommandHandler[S, C2, E]],
    mapper: C => C2
  ): CommandHandler[S, C, E] = { (s, c, ctx) =>
    val c2 = mapper(c)
    val f  = toCommandHandler[S, C2, E](handlers)
    f(s, c2, ctx)
  }
}
