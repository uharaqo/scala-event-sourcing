package io.github.uharaqo.es

import cats.effect.IO

/** request that comes from outside this system */
case class CommandInput(info: AggInfo, name: Fqcn, payload: Bytes)

// TODO: return a response message
case class CommandOutput(version: Version, message: String)

/** information related to the command handler */
case class CommandInfo[S, C, E](
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  handler: CommandHandler[S, C, E],
)

trait CommandProcessorEnv {

  /** to access states that are not managed by the aggregate */
  val stateLoaderFactory: StateLoaderFactory
  val eventWriter: EventWriter
}

/** Facade to process a command. Looks up a processor and dispatch a command */
type CommandProcessor = CommandInput => IO[EventRecords]

type PartialCommandProcessor = CommandProcessorEnv => PartialFunction[CommandInput, IO[EventRecords]]

type CommandInputParser[S, C, E] =
  PartialFunction[CommandInput, IO[(S, CommandHandlerContext[S, E]) => IO[EventRecords]]]
