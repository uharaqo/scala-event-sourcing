package io.github.uharaqo.es

import cats.effect.IO

case class DefaultCommandHandlerContext[S, E](
  info: StateInfo[S, E],
  id: AggId,
  metadata: Metadata,
  prevState: VersionedState[S],
  stateLoaderFactory: StateLoaderFactory,
) extends CommandHandlerContext[S, E]

extension [S, E](ctx: CommandHandlerContext[S, E]) {

  /** Generate events for this context */
  def save(events: E*): IO[EventRecords] =
    import cats.implicits.*
    events.zipWithIndex.traverse {
      case (e, i) =>
        ctx.info.eventCodec.convert(e).map { e =>
          EventRecord(ctx.info.name, ctx.id, ctx.prevState.version + i + 1, System.currentTimeMillis(), e)
        }
    }

  def fail(e: Exception): IO[EventRecords] = IO.raiseError(e)

  /** Load state of another aggregate */
  def withState[S2, E2](info: StateInfo[S2, E2], id: AggId): IO[(S2, CommandHandlerContext[S2, E2])] =
    for
      stateLoader <- ctx.stateLoaderFactory(info)
      verS        <- stateLoader.load(id)
      ctx2 = new DefaultCommandHandlerContext[S2, E2](info, id, ctx.metadata, verS, ctx.stateLoaderFactory)
    yield (verS.state, ctx2)
}

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
