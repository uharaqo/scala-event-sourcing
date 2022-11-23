package com.github.uharaqo.es.eventprojection

import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import cats.*, cats.implicits.*
import cats.effect.*, cats.effect.implicits.*
import cats.effect.std.Queue
import fs2.*

object EventProjections {
  sealed trait ProjectionTrigger

  object ProjectionTrigger:
    case object Timer                   extends ProjectionTrigger
    case class NewEvent(id: ResourceId) extends ProjectionTrigger

  case class SerializedEventRecord(id: ResourceIdentifier, version: Version, event: Serialized)

  trait ProjectionRepository {
    def load(resourceName: ResourceName, verGt: Version): Stream[IO, SerializedEventRecord]
  }

  // https://stackoverflow.com/a/65635013
  trait Sender:
    def send(t: ProjectionTrigger): Unit

  object Sender:
    def apply(bufferSize: Int): IO[(Sender, Stream[IO, ProjectionTrigger])] =
      for q <- Queue.bounded[IO, ProjectionTrigger](bufferSize)
      yield
        import unsafe.implicits.*
        val sender: Sender                        = (t: ProjectionTrigger) => q.offer(t).unsafeRunSync()
        def stream: Stream[IO, ProjectionTrigger] = Stream.eval(q.take) ++ stream
        (sender, stream)
}

import com.github.uharaqo.es.eventprojection.EventProjections.ProjectionRepository

class ProjectionBootstrap[E](
  private val eventDeserializer: EventDeserializer[E],
  private val projectionRepository: ProjectionRepository,
  private val resourceName: ResourceName,
  private val projection: (ResourceIdentifier, Version, E) => IO[Unit],
) {
  import com.github.uharaqo.es.eventprojection.EventProjections.*
  import unsafe.implicits.*

  def start: Sender = {
    val (sender: Sender, projectionTriggers: Stream[IO, ProjectionTrigger]) =
      Sender(64)
        // we have to run it preliminary to make `sender` available to external system
        .unsafeRunSync()

    // TODO: send events to the sender

    val projectionTriggerSubscriber: Stream[IO, FiberIO[List[Nothing]]] =
      projectionTriggers.evalMap { t =>
        val source =
          t match
            case ProjectionTrigger.Timer =>
              projectionRepository.load(resourceName, 0L)
            case ProjectionTrigger.NewEvent(id) =>
              projectionRepository.load(resourceName, 0L)

        val pipe = source.foreach { r =>
          for
            e <- eventDeserializer(r.event)
            _ <- projection(r.id, r.version, e)
          yield ()
        }

        pipe.compile.toList.start
      }

    // start in a separate fiber
    projectionTriggerSubscriber.compile.toList.start.unsafeRunSync()

    sender
  }
}
