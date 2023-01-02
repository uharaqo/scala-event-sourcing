package io.github.uharaqo.es

import cats.effect.IO

object CommandProcessor {

  /** combine PartialCommandProcessors to create a single facade */
  def apply(processors: Seq[PartialCommandProcessor]): CommandProcessor = {
    val f: PartialCommandProcessor =
      processors.foldLeft(PartialFunction.empty)((pf1, pf2) => pf2.orElse(pf1))
    input =>
      f.applyOrElse(
        input,
        _ => IO.raiseError(EsException.InvalidCommand(input.command))
      )
  }
}

object PartialCommandProcessor {
  import cats.implicits.*

  def apply[S, C, E](
    stateInfo: StateInfo[S, E],
    commandInfo: CommandInfo[S, C, E],
    stateLoader: StateLoader[S],
    stateLoaderFactory: StateLoaderFactory,
    eventWriter: EventWriter,
  ): PartialCommandProcessor = {
    val inputParser    = CommandInputParser(commandInfo)
    val contextFactory = CommandHandlerContextFactory(stateInfo, stateLoaderFactory)
    val contextProvider = (id: AggId, metadata: Metadata) =>
      stateLoader.load(id).map(prevState => contextFactory(id, metadata, prevState))
    val onSuccess = (ctx: CommandHandlerContext[S, E], output: CommandOutput) =>
      stateLoader.onSuccess(ctx.id, ctx.prevState, output)

    PartialCommandProcessor(inputParser, contextProvider, eventWriter, onSuccess)
  }

  def apply[S, E](
    inputParser: CommandInputParser[S, E],
    contextProvider: CommandHandlerContextProvider[S, E],
    eventWriter: EventWriter,
    onSuccess: CommandHandlerCallback[S, E],
  ): PartialCommandProcessor =
    import EsException.*

    new PartialCommandProcessor {
      override def isDefinedAt(input: CommandInput): Boolean = inputParser.isDefinedAt(input)
      override def apply(input: CommandInput): IO[CommandOutput] =
        val id = input.id
        for
          // create a command handler from the input
          handler <- inputParser(input)

          // recover the latest state by loading events
          ctx <- contextProvider(id, input.metadata)

          // invoke the hanlder
          output <- handler(ctx).handleErrorWith(t => IO.raiseError(CommandHandlerFailure(ctx.info.name, t)))

          // write the output events. it must succeed only when there's no conflicts
          success <- eventWriter.write(output)
          _       <- if !success then IO.raiseError(EventStoreConflict(ctx.info.name)) else IO.unit

          // callbacks
          - <- onSuccess(ctx, output)
        yield output
    }
}

object CommandHandlerContextFactory {
  def apply[S, E](
    stateInfo: StateInfo[S, E],
    stateLoaderFactory: StateLoaderFactory,
  ): CommandHandlerContextFactory[S, E] = (id, metadata, prevState) =>
    new DefaultCommandHandlerContext[S, E](stateInfo, id, metadata, prevState, stateLoaderFactory)
}

object CommandInputParser {
  def apply[S, C, E](commandInfo: CommandInfo[S, C, E]): CommandInputParser[S, E] =
    new CommandInputParser[S, E] {
      override def isDefinedAt(input: CommandInput): Boolean = commandInfo.fqcn == input.command
      override def apply(input: CommandInput): IO[CommandHandlerContext[S, E] => IO[CommandOutput]] =
        for command <- commandInfo.deserializer.convert(input.payload)
        yield ctx => commandInfo.commandHandler(ctx.prevState.state, command, ctx)
    }
}

/** dependencies used to load states and write events */
trait CommandProcessorEnv {

  /** write events into a DB */
  val eventRepository: EventRepository

  /** load events for projection */
  val projectionRepository: ProjectionRepository

  /** access states that are not managed by the aggregate */
  val stateLoaderFactory: StateLoaderFactory
}
