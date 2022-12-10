package com.github.uharaqo.es

import cats.effect.IO

/** Facade to process a command. Looks up a processor and dispatch a command */
type CommandProcessor = CommandRequest => IO[Seq[EventRecord]]

/** Provide a processor for a given command FQCN */
type CommandRegistry = Map[Fqcn, CommandRegistryEntry[_, _, _]]

case class CommandRegistryEntry[S, C, E](
  stateInfo: StateInfo[S, E],
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  handler: CommandHandler[S, C, E],
)

case class CommandRequest(info: AggInfo, name: Fqcn, command: Bytes)
