package io.github.uharaqo.es

import cats.effect.IO

case class CommandInput(info: AggInfo, name: Fqcn, payload: Bytes)

// TODO: return a response message
case class CommandOutput(version: Version, message: String)

case class ParsedCommandInput[S, C, E](
  stateInfo: StateInfo[S, E],
  handler: CommandHandler[S, C, E],
  command: C,
)

case class CommandInfo[S, C, E](
  stateInfo: StateInfo[S, E],
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  handler: CommandHandler[S, C, E],
)

trait CommandProcessorEnv {
  val stateProviderFactory: StateProviderFactory
  val eventWriter: EventWriter
}

/** Facade to process a command. Looks up a processor and dispatch a command */
type CommandProcessor = CommandInput => IO[EventRecords]

type PartialCommandProcessor = CommandProcessorEnv => PartialFunction[CommandInput, IO[EventRecords]]

type CommandInputParser[S, C, E] = PartialFunction[CommandInput, IO[ParsedCommandInput[S, C, E]]]
