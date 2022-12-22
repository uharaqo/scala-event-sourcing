package io.github.uharaqo.es

import cats.effect.IO

object CommandProcessor {
  def apply(env: CommandProcessorEnv, processors: Seq[PartialCommandProcessor]): CommandProcessor = {
    val f: PartialFunction[CommandInput, IO[EventRecords]] =
      processors.foldLeft(PartialFunction.empty)((pf1, pf2) => pf2(env).orElse(pf1))
    input => f.applyOrElse(input, _ => IO.raiseError(EsException.InvalidCommand(input.name)))
  }
}

object PartialCommandProcessor {
  def apply[S, C, E](inputParser: CommandInputParser[S, C, E]): PartialCommandProcessor = env =>
    new PartialFunction[CommandInput, IO[EventRecords]] {
      override def isDefinedAt(input: CommandInput): Boolean = inputParser.isDefinedAt(input)
      override def apply(input: CommandInput): IO[EventRecords] =
        val id = input.info.id
        for
          parsed <- inputParser.apply(input)
          command       = parsed.command
          stateInfo     = parsed.stateInfo
          handler       = parsed.handler
          stateProvider = env.stateProviderFactory(stateInfo)

          prevState <- stateProvider.load(id)

          ctx = new DefaultCommandHandlerContext(stateInfo, id, prevState.version, env.stateProviderFactory)
          records <-
            handler(prevState.state, command, ctx)
              .handleErrorWith(t => IO.raiseError(EsException.CommandHandlerFailure(t)))

          success <- env.eventWriter(records)
          _       <- if !success then IO.raiseError(EsException.EventStoreConflict()) else IO.unit

          _ <- stateProvider.afterWrite(id, prevState, records)
        yield records
    }
}

object CommandInputParser {
  def apply[S, C, E](commandInfo: CommandInfo[S, C, E]) = new CommandInputParser[S, C, E] {
    override def isDefinedAt(input: CommandInput): Boolean = commandInfo.fqcn == input.name
    override def apply(input: CommandInput): IO[ParsedCommandInput[S, C, E]] =
      for command <- commandInfo.deserializer(input.payload)
      yield ParsedCommandInput(commandInfo.stateInfo, commandInfo.handler, command)
  }
}

case class AggregateInfo[S, C, E, D](
  stateInfo: StateInfo[S, E],
  commandInfoFactory: D => CommandInfo[S, C, E],
)

object AggregateInfo {
  def apply[S, C, E, D](
    name: AggName,
    emptyState: S,
    eventCodec: Codec[E],
    eventHandler: EventHandler[S, E],
    commandFqcn: Fqcn,
    commandDeserializer: Deserializer[C],
    commandHandlerFactory: D => CommandHandler[S, C, E],
  ): AggregateInfo[S, C, E, D] =
    val stateInfo = StateInfo(name, emptyState, eventCodec, eventHandler)
    val commandInfo =
      (deps: D) => CommandInfo(stateInfo, commandFqcn, commandDeserializer, commandHandlerFactory(deps))

    AggregateInfo(stateInfo, commandInfo)
}
