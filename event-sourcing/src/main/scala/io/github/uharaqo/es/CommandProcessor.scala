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

/** information related to the command handler */
case class CommandInfo[S, C, E](
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  commandHandler: CommandHandler[S, C, E],
)

/** facade that looks up a [[PartialCommandHandler]] and dispatch the input */
type CommandProcessor = CommandInput => IO[CommandOutput]

/** standalone command processor that handles some [[CommandInput]]s */
type PartialCommandProcessor = CommandInput => Option[IO[CommandOutput]]

/** provide context for [[CommandTask]] */
type CommandHandlerContextProvider[S, E] = CommandInput => IO[CommandHandlerContext[S, E]]

/** emit output for [[CommandHandlerContext]] */
type CommandTask[S, E] = CommandHandlerContext[S, E] => IO[CommandOutput]

type CommandTaskProvider[S, E] = CommandInput => Option[IO[CommandTask[S, E]]]

/** invoked on success */
type CommandHandlerCallback[S, E] = (CommandHandlerContext[S, E], CommandOutput) => IO[Unit]
