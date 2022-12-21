package io.github.uharaqo.es

import cats.effect.IO
import io.github.uharaqo.es.EsException.*

object CommandRegistry {
  def apply[S, C, E](
    stateInfo: StateInfo[S, E],
    deserializers: Map[Fqcn, Deserializer[C]],
    handler: CommandHandler[S, C, E],
  ): CommandRegistry =
    deserializers.map { case (k, v) => (k, CommandRegistryEntry(stateInfo, k, v, handler)) }
}

object CommandProcessor {
  def apply(
    commandRegistry: CommandRegistry,
    stateProviderFactory: StateProviderFactory,
    eventWriter: EventWriter,
  ): CommandProcessor = { request =>
    val id          = request.info.id
    val commandName = request.name
    val rawCommand  = request.payload

    for
      entry   <- IO.fromOption(commandRegistry.get(commandName))(InvalidCommand(commandName))
      command <- entry.deserializer(rawCommand)

      stateProvider = stateProviderFactory.create(entry.stateInfo)
      prevState <- stateProvider.load(id)

      ctx = new DefaultCommandHandlerContext(entry.stateInfo, id, prevState.version, stateProviderFactory)
      responses <-
        entry
          .handler(prevState.state, command, ctx)
          .handleErrorWith(t => IO.raiseError(CommandHandlerFailure(t)))

      success <- eventWriter(responses)
      _       <- if !success then IO.raiseError(EventStoreConflict()) else IO.unit

      _ <- stateProvider.afterWrite(id, prevState, responses)
    yield responses
  }
  // .recoverWith { case t: EventStoreConflict => if (retryOnConflict) handle else IO.raiseError(t) }
}
