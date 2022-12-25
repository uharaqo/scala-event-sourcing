package io.github.uharaqo.es

import cats.syntax.option.*

sealed class EsException(message: String, cause: Option[Throwable] = none) extends Exception(message, cause.orNull)

object EsException:
  final case class InvalidCommand(name: AggName, cause: Option[Throwable] = none)
      extends EsException(s"Invalid Command: $name", cause)

  final case class EventStoreFailure(t: Throwable) extends EsException("Failed to store event", t.some)

  final case class EventStoreConflict(name: AggName)
      extends EsException("Failed to store event due to conflict: $name", none)

  final case class EventLoadFailure(t: Throwable) extends EsException("Failed to load event", t.some)

  final case class CommandAlreadyRegistered(fqcn: Fqcn) extends EsException(s"Command already registered: $fqcn", none)

  final case class CommandHandlerFailure(name: AggName, t: Throwable)
      extends EsException(s"Command handler failure: $name", t.some)

  case object UnexpectedException extends EsException(s"Unexpected Exception", none)
