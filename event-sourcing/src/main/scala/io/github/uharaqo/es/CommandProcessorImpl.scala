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
  def apply[S, C, E](
    inputParser: CommandInputParser[S, C, E],
    stateInfo: StateInfo[S, E],
    stateLoader: StateLoader[S]
  ): PartialCommandProcessor = env =>
    new PartialFunction[CommandInput, IO[EventRecords]] {
      override def isDefinedAt(input: CommandInput): Boolean = inputParser.isDefinedAt(input)
      override def apply(input: CommandInput): IO[EventRecords] =
        val id = input.info.id
        for
          handler <- inputParser(input)

          prevState <- stateLoader.load(id)

          ctx = new DefaultCommandHandlerContext(stateInfo, id, prevState.version, env.stateLoaderFactory)
          records <-
            handler(prevState.state, ctx)
              .handleErrorWith(t => IO.raiseError(EsException.CommandHandlerFailure(t)))

          success <- env.eventWriter(records)
          _       <- if !success then IO.raiseError(EsException.EventStoreConflict()) else IO.unit

          _ <- stateLoader.afterWrite(id, prevState, records)
        yield records
    }
}

object CommandInputParser {
  def apply[S, C, E](commandInfo: CommandInfo[S, C, E]): CommandInputParser[S, CommandInput, E] =
    new CommandInputParser[S, CommandInput, E] {
      override def isDefinedAt(input: CommandInput): Boolean = commandInfo.fqcn == input.name
      override def apply(input: CommandInput): IO[(S, CommandHandlerContext[S, E]) => IO[EventRecords]] =
        for command <- commandInfo.deserializer(input.payload)
        yield (s, ctx) => commandInfo.handler(s, command, ctx)
    }
}
