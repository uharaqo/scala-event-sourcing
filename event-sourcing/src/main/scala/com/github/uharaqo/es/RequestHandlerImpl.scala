package com.github.uharaqo.es

object CommandRegistry {
  def apply[S, C, E](
    stateInfo: StateInfo[S, E],
    deserializers: Map[Fqcn, Deserializer[C]],
    handler: CommandHandler[S, C, E],
  ): CommandRegistry =
    deserializers.map { case (k, v) => (k, CommandRegistryEntry(stateInfo, k, v, handler)) }
}

object CommandProcessor {
  import cats.effect.IO
  import com.github.uharaqo.es.EsException.*

  def apply(
    commandRegistry: CommandRegistry,
    stateProvider: StateProvider,
    eventWriter: EventWriter,
  ): CommandProcessor = { request =>
    val id          = request.info.id
    val commandName = request.name

    for
      info    <- IO.fromOption(commandRegistry.get(commandName))(InvalidCommand(commandName))
      command <- info.deserializer(request.command)
      verS    <- stateProvider.load(info.stateInfo, id)

      ctx = new DefaultCommandHandlerContext(info.stateInfo, id, verS.version, stateProvider)
      responses <-
        info
          .handler(verS.state, command, ctx)
          .recoverWith(t => IO.raiseError(CommandHandlerFailure(t)))

      success <- eventWriter(responses)
      _       <- if (!success) IO.raiseError(EventStoreConflict()) else IO.unit
    yield responses
  }
  // .recoverWith { case t: EventStoreConflict => if (retryOnConflict) handle else IO.raiseError(t) }
}
