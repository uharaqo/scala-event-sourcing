package io.github.uharaqo.es

import cats.effect.IO

class DefaultCommandHandlerContext[S, E](
  override val info: StateInfo[S, E],
  override val id: AggId,
  override val prevVer: Version,
  stateProviderFactory: StateProviderFactory,
) extends CommandHandlerContext[S, E] {

  import cats.implicits.*

  override def save(events: E*): IO[EventRecords] =
    events.zipWithIndex.traverse {
      case (e, i) =>
        info.eventCodec.serializer(e).map { e =>
          EventRecord(info.name, id, prevVer + i + 1, System.currentTimeMillis(), e)
        }
    }

  override def withState[S2, E2](info: StateInfo[S2, E2], id: AggId)(
    handler: (S2, CommandHandlerContext[S2, E2]) => IO[EventRecords]
  ): IO[EventRecords] =
    for
      verS <- stateProviderFactory.create(info).load(id)
      ctx = new DefaultCommandHandlerContext[S2, E2](info, id, verS.version, stateProviderFactory)
      ress <- handler(verS.state, ctx)
    yield ress
}

type SelectiveCommandHandler[S, C, E] = (s: S, c: C, ctx: CommandHandlerContext[S, E]) => Option[IO[EventRecords]]

object SelectiveCommandHandler {
  def toCommandHandler[S, C, E, D](
    handlers: Seq[D => SelectiveCommandHandler[S, C, E]]
  ): D => CommandHandler[S, C, E] = { dep => (s, c, ctx) =>
    handlers.map(_(dep)).map(_(s, c, ctx)).find(_.isDefined).flatten.getOrElse(IO.pure(Seq.empty))
  }
}
