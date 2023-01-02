package io.github.uharaqo.es

import cats.effect.IO

trait CommandHandler[S, C, E]:
  def apply(s: S, c: C, ctx: CommandHandlerContext[S, E]): IO[EventRecords]

type PartialCommandHandler[S, C, E] = (S, CommandHandlerContext[S, E]) => PartialFunction[C, IO[EventRecords]]

type CommandHandlerContextFactory[S, E] = (AggId, Metadata, VersionedState[S]) => CommandHandlerContext[S, E]

/** Helper for command processor */
trait CommandHandlerContext[S, E]:
  val info: StateInfo[S, E]
  val id: AggId
  val metadata: Metadata
  val prevState: VersionedState[S]
  val stateLoaderFactory: StateLoaderFactory
