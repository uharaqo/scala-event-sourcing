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
  def save(events: E*): IO[CommandOutput] =
    import cats.implicits.*
    events.zipWithIndex
      .traverse {
        case (e, i) =>
          ctx.info.eventCodec.convert(e).map { e =>
            val name    = ctx.info.name
            val id      = ctx.id
            val version = ctx.prevState.version + i + 1
            val event   = e

            EventOutput(name, id, version, event)
          }
      }
      .map(CommandOutput(_))

  def fail(e: Exception): IO[CommandOutput] = IO.raiseError(e)

  /** Load state of another aggregate */
  def withState[S2, E2](info: StateInfo[S2, E2], id: AggId): IO[(S2, CommandHandlerContext[S2, E2])] =
    for
      stateLoader <- ctx.stateLoaderFactory(info)
      verS        <- stateLoader.load(id)
      ctx2 = new DefaultCommandHandlerContext[S2, E2](info, id, ctx.metadata, verS, ctx.stateLoaderFactory)
    yield (verS.state, ctx2)
}

object CommandHandlerContextFactory {
  def apply[S, E](
    stateInfo: StateInfo[S, E],
    stateLoaderFactory: StateLoaderFactory,
  ): CommandHandlerContextFactory[S, E] = (id, metadata, prevState) =>
    new DefaultCommandHandlerContext[S, E](stateInfo, id, metadata, prevState, stateLoaderFactory)
}

object PartialCommandHandler {
  def toCommandHandler[C, S, E](handlers: Seq[PartialCommandHandler[C, S, E]]): CommandHandler[C, S, E] =
    val handler = (c: C) => (for (h <- handlers; f <- h.lift(c)) yield f).headOption
    (c, s, ctx) =>
      handler(c) match
        case Some(f) => f(s, ctx)
        case None    => IO.raiseError(EsException.UnhandledCommand(ctx.info.name, c.getClass.getCanonicalName))

  def toCommandHandler[C, S, E, C2](
    handlers: Seq[PartialCommandHandler[C2, S, E]],
    mapper: C => C2
  ): CommandHandler[C, S, E] = {
    val f = toCommandHandler[C2, S, E](handlers)

    (c, s, ctx) => f(mapper(c), s, ctx)
  }

  import scala.reflect.ClassTag
  def handlerFactory[C, S, E] = new ABC[C, S, E] {}

  def handlerFor[C, S, E, CC <: C: ClassTag](
//    f: (CC, S, CommandHandlerContext[S, E]) => IO[CommandOutput]
    f: CommandHandler[CC, S, E]
  ): PartialCommandHandler[C, S, E] =
    new PartialFunction[C, (S, CommandHandlerContext[S, E]) => IO[CommandOutput]] {
      private val t = summon[ClassTag[CC]]
      override def isDefinedAt(c: C): Boolean =
        t.runtimeClass.isInstance(c)
      override def apply(c: C): (S, CommandHandlerContext[S, E]) => IO[CommandOutput] = (s, ctx) =>
        f(t.unapply(c).get, s, ctx)
    }

  trait ABC[C, S, E] {

    import scala.reflect.ClassTag
    def handlerFor[CC <: C: ClassTag](f: CommandHandler[CC, S, E]): PartialCommandHandler[C, S, E] =
      PartialCommandHandler.handlerFor(f)
  }
}
