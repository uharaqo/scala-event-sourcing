package io.github.uharaqo.es

import cats.effect.IO

import scala.annotation.targetName

trait CommandHandler[S, C, E]:
  def apply(state: S, command: C, ctx: CommandHandlerContext[S, E]): IO[CommandOutput]

type PartialCommandHandler[S, C, E] = PartialFunction[C, (S, CommandHandlerContext[S, E]) => IO[CommandOutput]]

type CommandHandlerContextFactory[S, E] = (AggId, Metadata, VersionedState[S]) => CommandHandlerContext[S, E]

case class CommandOutput(events: Seq[EventOutput], metadata: Metadata = Metadata.empty):
  def version: Option[Version] = events.lastOption.map(_.version)
  @`inline` @targetName("concat") final def ++(other: CommandOutput): CommandOutput =
    CommandOutput(events ++ other.events, metadata ++ other.metadata)

/** Helper for command processor */
trait CommandHandlerContext[S, E]:
  val info: StateInfo[S, E]
  val id: AggId
  val metadata: Metadata
  val prevState: VersionedState[S]
  val stateLoaderFactory: StateLoaderFactory
