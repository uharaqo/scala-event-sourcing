package io.github.uharaqo.es

import cats.effect.IO

import scala.annotation.targetName

/** request that comes from outside this system */
case class CommandInput(
  name: AggName,
  id: AggId,
  command: Fqcn,
  payload: Bytes,
  metadata: Metadata = Metadata.empty
)

case class CommandOutput(events: Seq[EventOutput], metadata: Metadata = Metadata.empty):
  def version: Option[Version] = events.lastOption.map(_.version)
  @`inline` @targetName("concat") final def ++(other: CommandOutput): CommandOutput =
    CommandOutput(events ++ other.events, metadata ++ other.metadata)

/** information related to the command handler */
case class CommandInfo[S, C, E](
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  commandHandler: CommandHandler[S, C, E],
)

/** facade to process a command. Looks up a processor and dispatch a command */
type CommandProcessor = CommandInput => IO[CommandOutput]

/** standalone CommandProcessor that handles some of the CommandInputs */
type PartialCommandProcessor = PartialFunction[CommandInput, IO[CommandOutput]]

/** create a command handler from a CommandInput */
type CommandInputParser[S, E] = PartialFunction[CommandInput, IO[CommandHandlerContext[S, E] => IO[CommandOutput]]]

/** provide context for a command handler */
type CommandHandlerContextProvider[S, E] = (AggId, Metadata) => IO[CommandHandlerContext[S, E]]

/** invoked on success */
type CommandHandlerCallback[S, E] = (CommandHandlerContext[S, E], CommandOutput) => IO[Unit]
