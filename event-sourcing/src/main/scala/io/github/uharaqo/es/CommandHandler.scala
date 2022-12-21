package io.github.uharaqo.es

import cats.effect.IO

type CommandHandler[S, C, E] = (S, C, CommandHandlerContext[S, E]) => IO[EventRecords]

/** Helper for command processor */
trait CommandHandlerContext[S, E]:
  val info: StateInfo[S, E]
  val id: AggId
  val prevVer: Version

  def save(events: E*): IO[EventRecords]

  def fail(e: Exception): IO[EventRecords] = IO.raiseError(e)

  def withState[S2, E2](
    info: StateInfo[S2, E2],
    id: AggId
  )(handler: (S2, CommandHandlerContext[S2, E2]) => IO[EventRecords]): IO[EventRecords]
