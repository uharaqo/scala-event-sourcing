package io.github.uharaqo.es

import cats.effect.IO

object CommandProcessor {

  /** combine PartialCommandProcessors to create a single facade */
  def apply(processors: Seq[PartialCommandProcessor]): CommandProcessor = {
    val f: PartialFunction[CommandInput, IO[EventRecords]] =
      processors.foldLeft(PartialFunction.empty)((pf1, pf2) => pf2.orElse(pf1))
    input =>
      f.applyOrElse(
        input,
        _ => IO.raiseError(EsException.InvalidCommand(input.command))
      ).map(CommandOutput.apply)
  }
}

object PartialCommandProcessor {
  import cats.implicits.*

  def apply[S, C, E](
    stateInfo: StateInfo[S, E],
    commandInfo: CommandInfo[S, C, E],
    stateLoader: StateLoader[S],
    stateLoaderFactory: StateLoaderFactory,
    eventWriter: EventWriter
  ): PartialCommandProcessor = {
    val inputParser = CommandInputParser(commandInfo)

    val contextFactory: (AggId, VersionedState[S]) => CommandHandlerContext[S, E] =
      (id, prevState) => new DefaultCommandHandlerContext(stateInfo, id, prevState, stateLoaderFactory)

    val contextProvider: CommandHandlerContextProvider[S, E] = id =>
      stateLoader.load(id).map(prevState => contextFactory(id, prevState))

    val onSuccess: CommandHandlerCallback[S, E] = (ctx, records) =>
      stateLoader.onSuccess(ctx.id, ctx.prevState, records)

    PartialCommandProcessor(inputParser, contextProvider, eventWriter, onSuccess)
  }

  def apply[S, C, E](
    inputParser: CommandInputParser[S, C, E],
    contextProvider: CommandHandlerContextProvider[S, E],
    eventWriter: EventWriter,
    onSuccess: CommandHandlerCallback[S, E],
  ): PartialCommandProcessor =
    import EsException.*

    new PartialFunction[CommandInput, IO[EventRecords]] {
      override def isDefinedAt(input: CommandInput): Boolean = inputParser.isDefinedAt(input)
      override def apply(input: CommandInput): IO[EventRecords] =
        val id = input.id
        for
          // create a command handler from the input
          handler <- inputParser(input)

          // recover the latest state by loading events
          ctx <- contextProvider(id)

          // invoke the hanlder
          records <- handler(ctx).handleErrorWith(t => IO.raiseError(CommandHandlerFailure(ctx.info.name, t)))

          // write the output events. it must succeed only when there's no conflicts
          success <- eventWriter.write(records)
          _       <- if !success then IO.raiseError(EventStoreConflict(ctx.info.name)) else IO.unit

          // callbacks
          - <- onSuccess(ctx, records)
        yield records
    }
}

object CommandInputParser {
  def apply[S, C, E](commandInfo: CommandInfo[S, C, E]): CommandInputParser[S, CommandInput, E] =
    new CommandInputParser[S, CommandInput, E] {
      override def isDefinedAt(input: CommandInput): Boolean = commandInfo.fqcn == input.command
      override def apply(input: CommandInput): IO[CommandHandlerContext[S, E] => IO[EventRecords]] =
        for command <- commandInfo.deserializer.convert(input.payload)
        yield ctx => commandInfo.commandHandler(ctx.prevState.state, command, ctx)
    }
}
