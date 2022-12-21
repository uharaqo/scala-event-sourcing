package io.github.uharaqo.es

import cats.effect.IO

/** Facade to process a command. Looks up a processor and dispatch a command */
trait CommandProcessor {
  def apply(input: CommandInput): IO[EventRecords]
}

/** Provide a processor for a given command FQCN */
type CommandRegistry = Map[Fqcn, CommandInfo[?, ?, ?]] // TODO: a better interface?

case class CommandInput(info: AggInfo, name: Fqcn, payload: Bytes)

case class CommandOutput(version: Version, message: String)

case class CommandInfo[S, C, E](
  stateInfo: StateInfo[S, E],
  fqcn: Fqcn,
  deserializer: Deserializer[C],
  handler: CommandHandler[S, C, E],
)

object CommandProcessor {
  def apply(
    commandRegistry: CommandRegistry,
    stateProviderFactory: StateProviderFactory,
    eventWriter: EventWriter,
  ): CommandProcessor = { input =>
    import io.github.uharaqo.es.EsException.*

    val id          = input.info.id
    val commandName = input.name
    val rawCommand  = input.payload

    for
      commandInfo <- IO.fromOption(commandRegistry.get(commandName))(InvalidCommand(commandName))
      command     <- commandInfo.deserializer(rawCommand)

      stateProvider = stateProviderFactory.create(commandInfo.stateInfo)
      prevState <- stateProvider.load(id)

      ctx = new DefaultCommandHandlerContext(commandInfo.stateInfo, id, prevState.version, stateProviderFactory)
      records <-
        commandInfo
          .handler(prevState.state, command, ctx)
          .handleErrorWith(t => IO.raiseError(CommandHandlerFailure(t)))

      success <- eventWriter(records)
      _       <- if !success then IO.raiseError(EventStoreConflict()) else IO.unit

      _ <- stateProvider.afterWrite(id, prevState, records)
    yield records
  }
  // .handleErrorWith { case t: EventStoreConflict => if (retryOnConflict) handle else IO.raiseError(t) }
}
