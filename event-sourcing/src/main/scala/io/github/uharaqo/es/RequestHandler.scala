package io.github.uharaqo.es

import cats.effect.IO

/** Facade to process a command. Looks up a processor and dispatch a command */
type CommandProcessor = CommandInput => IO[EventRecords]

/** Provide a processor for a given command FQCN */
type CommandRegistry = Map[Fqcn, CommandInfo[?, ?, ?]]

case class CommandInput(info: AggInfo, name: Fqcn, payload: Bytes)

case class CommandOutput(version: Version, message: String)

case class CommandInfo[S, C, E](
  stateInfo: StateInfo[S, E],
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  handler: CommandHandler[S, C, E],
)
