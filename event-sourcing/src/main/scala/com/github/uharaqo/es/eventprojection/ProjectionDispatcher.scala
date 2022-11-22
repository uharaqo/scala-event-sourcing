package com.github.uharaqo.es.eventprojection

import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import cats.*, cats.implicits.*
import cats.effect.*, cats.effect.implicits.*
import fs2.*
import scala.concurrent.ExecutionContext
import com.github.uharaqo.es.io.sql.DoobieEventRepository

// TODO: separate out module and add grpc
trait ProjectionDispatcher {
  def start(verGt: Version): Stream[IO, (ResourceIdentifier, Version, Serialized)]

  def onChange(id: ResourceId): Unit
}
trait ProjectionRepository {
  def load(resourceName: ResourceName, verGt: Version): Stream[IO, (ResourceIdentifier, Version, Serialized)]
}

class DefaultProjectionDispatcher(
  private val targetResource: ResourceName,
  private val repo: ProjectionRepository,
) extends ProjectionDispatcher {

  override def start(verGt: Version): Stream[IO, (ResourceIdentifier, Version, Serialized)] =
    load(0L)

  override def onChange(id: ResourceId): Unit = ???

  def load(verGt: Version): Stream[IO, (ResourceIdentifier, Version, Serialized)] =
    repo.load(targetResource, verGt)
}
