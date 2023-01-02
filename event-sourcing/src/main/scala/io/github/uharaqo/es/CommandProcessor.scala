package io.github.uharaqo.es

import cats.effect.IO

/** request that comes from outside this system */
case class CommandInput(
  name: AggName,
  id: AggId,
  command: Fqcn,
  payload: Bytes,
  metadata: Metadata = Metadata.empty
)

case class CommandOutput(records: EventRecords, metadata: Metadata = Metadata.empty) {
  def version: Option[Version] = records.lastOption.map(_.version)
}

/** information related to the command handler */
case class CommandInfo[S, C, E](
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  commandHandler: CommandHandler[S, C, E],
)

/** facade to process a command. Looks up a processor and dispatch a command */
type CommandProcessor = CommandInput => IO[CommandOutput]

/** standalone CommandProcessor that handles some of the CommandInputs */
type PartialCommandProcessor = PartialFunction[CommandInput, IO[EventRecords]]

/** create a command handler from a CommandInput */
type CommandInputParser[S, C, E] =
  PartialFunction[CommandInput, IO[CommandHandlerContext[S, E] => IO[EventRecords]]]

/** provide context for a command handler */
type CommandHandlerContextProvider[S, E] = (AggId, Metadata) => IO[CommandHandlerContext[S, E]]

/** invoked on success */
type CommandHandlerCallback[S, E] = (CommandHandlerContext[S, E], EventRecords) => IO[Unit]

/** dependencies used to load states and write events */
trait CommandProcessorEnv {

  /** write events into a DB */
  val eventRepository: EventRepository

  /** load events for projection */
  val projectionRepository: ProjectionRepository

  /** access states that are not managed by the aggregate */
  val stateLoaderFactory: StateLoaderFactory
}
