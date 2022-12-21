package io.github.uharaqo.es

import cats.effect.IO

/** Facade to process a command. Looks up a processor and dispatch a command */
type CommandProcessor = CommandRequest => IO[EventRecords]

/** Provide a processor for a given command FQCN */
type CommandRegistry = Map[Fqcn, CommandRegistryEntry[?, ?, ?]]

case class CommandRegistryEntry[S, C, E](
  stateInfo: StateInfo[S, E],
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  handler: CommandHandler[S, C, E],
)

case class CommandRequest(info: AggInfo, name: Fqcn, payload: Bytes)

case class CommandResult(version: Version, message: String)
