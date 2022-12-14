package io.github.uharaqo.es

import cats.syntax.option.*

sealed class ProjectionException protected (message: String, cause: Option[Throwable])
    extends Exception(message, cause.orNull)

object ProjectionException:
  final case class UnrecoverableException(message: String, cause: Option[Throwable] = none)
      extends ProjectionException(message, cause):
    def this(message: String) = this(message, none)

  final case class TemporaryException(message: String, cause: Option[Throwable])
      extends ProjectionException(message, cause):
    def this(message: String) = this(message, none)
