package io.github.uharaqo.es

import cats.effect.IO
import io.github.uharaqo.es.EsException.*

object CommandProcessor {

  /** combine [[PartialCommandProcessor]]s into a single processor */
  def apply(processors: Seq[PartialCommandProcessor]): CommandProcessor = input =>
    (for (p <- processors; t <- p(input)) yield t).headOption
      .getOrElse(IO.raiseError(UnknownCommand(input.name, input.command)))
}

object PartialCommandProcessor {
  import cats.implicits.*

  def apply[C, S, E](
    stateInfo: StateInfo[S, E],
    commandInfo: CommandInfo[C, S, E],
    stateLoader: StateLoader[S],
    stateLoaderFactory: StateLoaderFactory,
    eventWriter: EventWriter,
  ): PartialCommandProcessor = {
    val taskProvider   = CommandTaskProvider(commandInfo)
    val contextFactory = CommandHandlerContextFactory(stateInfo, stateLoaderFactory)
    val contextProvider = (input: CommandInput) =>
      for prevState <- stateLoader.load(input.id) yield contextFactory(input.id, input.metadata, prevState)
    val onSuccess = (ctx: CommandHandlerContext[S, E], output: CommandOutput) =>
      stateLoader.onSuccess(ctx.id, ctx.prevState, output)

    PartialCommandProcessor(taskProvider, contextProvider, eventWriter, onSuccess)
  }

  def apply[S, E](
    taskProvider: CommandTaskProvider[S, E],
    contextProvider: CommandHandlerContextProvider[S, E],
    eventWriter: EventWriter,
    onSuccess: CommandHandlerCallback[S, E],
  ): PartialCommandProcessor = input =>
    for taskIO <- taskProvider(input) yield for {
      // recover the latest state by loading events
      ctx <- contextProvider(input)

      // invoke the task
      task   <- taskIO
      output <- task(ctx).handleErrorWith(t => IO.raiseError(CommandHandlerFailure(ctx.info.name, input.command, t)))

      // write the output events
      success <- eventWriter.write(output)
      _       <- if !success then IO.raiseError(EventStoreConflict(ctx.info.name)) else IO.unit
      _       <- onSuccess(ctx, output)
    } yield output
}

object CommandTaskProvider {
  def apply[C, S, E](commandInfo: CommandInfo[C, S, E]): CommandTaskProvider[S, E] =
    input =>
      Option.when(commandInfo.fqcn == input.command) {
        for command <- commandInfo.deserializer.convert(input.payload)
        yield ctx => commandInfo.commandHandler(command, ctx.prevState.state, ctx)
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
