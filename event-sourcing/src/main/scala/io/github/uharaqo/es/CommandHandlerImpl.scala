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

trait AggregateHelper[C, S, E, D] {

  import scala.reflect.ClassTag
  def commandHandlerFor[C2 <: C: ClassTag](f: D => CommandHandler[C2, S, E]): D => PartialCommandHandler[C, S, E] =
    (d: D) => PartialCommandHandler.commandHandlerFor(f(d))

  def commandHandler(handlers: (D => PartialCommandHandler[C, S, E])*): D => CommandHandler[C, S, E] =
    PartialCommandHandler.toCommandHandler[C, S, E, D](handlers*)

  def convert[C2](mapper: C => C2) = new MappedAggregateHelper[C, S, E, D, C2](mapper)

  def eventHandler(f: (S, E) => S): EventHandler[S, E] = (s, e) => scala.util.Try(f(s, e))
}

class MappedAggregateHelper[C, S, E, D, C2](private val mapper: C => C2) {
  import scala.reflect.ClassTag
  def commandHandlerFor[C3 <: C2: ClassTag](f: D => CommandHandler[C3, S, E]): D => PartialCommandHandler[C2, S, E] =
    (d: D) => PartialCommandHandler.commandHandlerFor(f(d))

  def commandHandler(handlers: (D => PartialCommandHandler[C2, S, E])*): D => CommandHandler[C, S, E] = {
    val h = PartialCommandHandler.toCommandHandler[C2, S, E, D](handlers*)
    d => (c, s, ctx) => h(d)(mapper(c), s, ctx)
  }
}

object AggregateHelper {
  def apply[C, S, E, D] = new AggregateHelper[C, S, E, D] {}
}

object PartialCommandHandler {
  def toCommandHandler[C, S, E, D](handlers: (D => PartialCommandHandler[C, S, E])*): D => CommandHandler[C, S, E] = {
    import cats.implicits.*
    for hs <- handlers.traverse(identity)
    yield merge(hs)
  }

  private def merge[C, S, E](handlers: Seq[PartialCommandHandler[C, S, E]]): CommandHandler[C, S, E] =
    val handler = (c: C) => (for (h <- handlers; f <- h.lift(c)) yield f).headOption
    (c, s, ctx) =>
      handler(c) match
        case Some(f) => f(s, ctx)
        case None    => IO.raiseError(EsException.UnhandledCommand(ctx.info.name, c.getClass.getCanonicalName))

  import scala.reflect.ClassTag
  def commandHandlerFor[C, S, E, C2 <: C: ClassTag](f: CommandHandler[C2, S, E]): PartialCommandHandler[C, S, E] =
    new PartialFunction[C, (S, CommandHandlerContext[S, E]) => IO[CommandOutput]] {
      private val t = summon[ClassTag[C2]]
      override def isDefinedAt(c: C): Boolean =
        t.runtimeClass.isInstance(c)
      override def apply(c: C): (S, CommandHandlerContext[S, E]) => IO[CommandOutput] = (s, ctx) =>
        f(t.unapply(c).get, s, ctx)
    }
}
