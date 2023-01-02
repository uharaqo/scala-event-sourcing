package io.github.uharaqo.es

import cats.effect.IO

trait CommandHandler[S, C, E]:
  def apply(state: S, command: C, ctx: CommandHandlerContext[S, E]): IO[CommandOutput]

type PartialCommandHandler[S, C, E] = (S, CommandHandlerContext[S, E]) => PartialFunction[C, IO[CommandOutput]]

type CommandHandlerContextFactory[S, E] = (AggId, Metadata, VersionedState[S]) => CommandHandlerContext[S, E]

/** Helper for command processor */
trait CommandHandlerContext[S, E]:
  val info: StateInfo[S, E]
  val id: AggId
  val metadata: Metadata
  val prevState: VersionedState[S]
  val stateLoaderFactory: StateLoaderFactory
