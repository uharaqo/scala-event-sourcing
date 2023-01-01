package io.github.uharaqo.es

import cats.effect.IO

trait CommandHandler[S, C, E]:
  def apply(s: S, c: C, ctx: CommandHandlerContext[S, E]): IO[EventRecords]

type PartialCommandHandler[S, C, E] = (S, CommandHandlerContext[S, E]) => PartialFunction[C, IO[EventRecords]]

type CommandHandlerContextFactory[S, E] = (AggId, VersionedState[S], Metadata) => CommandHandlerContext[S, E]

/** Helper for command processor */
trait CommandHandlerContext[S, E]:
  val info: StateInfo[S, E]
  val id: AggId
  val metadata: Metadata
  val prevState: VersionedState[S]

  def save(events: E*): IO[EventRecords]

  def fail(e: Exception): IO[EventRecords] = IO.raiseError(e)

  /** Load state of another aggregate */
  def withState[S2, E2](info: StateInfo[S2, E2], id: AggId): IO[(S2, CommandHandlerContext[S2, E2])]
