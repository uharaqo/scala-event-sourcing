package com.github.uharaqo.es

import cats.syntax.option.*

sealed class EsException(message: String, cause: Option[Throwable] = none) extends Exception(message, cause.orNull)

object EsException:
  case class InvalidCommand(name: AggName, cause: Option[Throwable] = none)
    extends EsException(s"Invalid Command: $name", cause)

  case class EventStoreFailure(t: Throwable) extends EsException("Failed to store event", t.some)

  case class EventStoreConflict() extends EsException("Failed to store event due to conflict", none)

  case class EventLoadFailure(t: Throwable) extends EsException("Failed to load event", t.some)

  case class CommandAlreadyRegistered(fqcn: Fqcn) extends EsException(s"Command already registered: $fqcn", none)

  case class CommandHandlerFailure(t: Throwable) extends EsException(s"Command handler failure", t.some)

  case object UnexpectedException extends EsException(s"Unexpected Exception", none)

sealed class ProjectionException protected (message: String, cause: Throwable) extends Exception(message, cause)

object ProjectionException:
  case class UnrecoverableException(message: String, cause: Throwable) extends ProjectionException(message, cause):
    def this(message: String) = this(message, null: Throwable)
  case class TemporaryException(message: String, cause: Throwable) extends ProjectionException(message, cause):
    def this(message: String) = this(message, null: Throwable)
