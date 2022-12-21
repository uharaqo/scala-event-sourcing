package io.github.uharaqo.es

import cats.effect.IO
import io.github.uharaqo.es.EsException.*

object CommandRegistry {
  def apply[S, C, E](
    stateInfo: StateInfo[S, E],
    deserializers: Map[Fqcn, Deserializer[C]],
    handler: CommandHandler[S, C, E],
  ): CommandRegistry =
    deserializers.map { case (k, v) => (k, CommandInfo(stateInfo, k, v, handler)) }
}

object CommandProcessor {
  def apply(
    commandRegistry: CommandRegistry,
    stateProviderFactory: StateProviderFactory,
    eventWriter: EventWriter,
  ): CommandProcessor = { input =>
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
