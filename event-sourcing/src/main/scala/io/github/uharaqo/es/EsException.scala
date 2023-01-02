package io.github.uharaqo.es

import cats.syntax.option.*

sealed class EsException(message: String, cause: Option[Throwable] = none) extends Exception(message, cause.orNull)

object EsException:
  /** No [[CommandProcessor]] was found */
  final case class UnknownCommand(name: AggName, command: String)
      extends EsException(s"Unknown command [$name]: $command", none)

  final case class CommandHandlerFailure(name: AggName, command: String, t: Throwable)
      extends EsException(s"Command handler failure [$name]: $command", t.some)

  /** [[CommandHandler]] couldn't find a handler for the command */
  final case class UnhandledCommand(name: AggName, command: String)
      extends EsException(s"Unhandled command [$name]: $command", none)

  final case class EventStoreFailure(t: Throwable) extends EsException("Failed to store event", t.some)

  final case class EventStoreConflict(name: AggName)
      extends EsException("Failed to store event due to conflict: $name", none)

  final case class EventLoadFailure(t: Throwable) extends EsException("Failed to load event", t.some)

  case object UnexpectedException extends EsException(s"Unexpected Exception", none)
