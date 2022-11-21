package com.github.uharaqo.es.eventsourcing

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import fs2.Stream

import java.time.Instant

object EventSourcing {

  type ResourceName       = String
  type ResourceIdentifier = String
  type Version            = Long
  type Fqcn               = String

  case class ResourceId(name: ResourceName, id: ResourceIdentifier)
  case class VersionedEvent(version: Version, event: ByteArray)
  case class VersionedState[S](version: Version, state: S)

  // serializers
  type RawCommand = String
  type ByteArray  = Array[Byte]

  type CommandDeserializer[C] = RawCommand => IO[C]
  type EventSerializer[E]     = E => IO[ByteArray]
  type EventDeserializer[E]   = ByteArray => IO[E]

  // command handler
  type CommandHandler[S, C, E] = (S, C) => IO[Seq[E]]

  case class CommandRequest(id: ResourceId, name: Fqcn, command: RawCommand)
  case class CommandResponse(id: ResourceId, events: Seq[VersionedEvent])

  type CommandRouter = CommandRequest => IO[CommandResponse]
  object CommandRouter {
    def apply(commandRegistry: CommandRegistry): CommandRouter = { request =>
      for {
        processor <- IO.fromOption(commandRegistry(request.name))(EsException.InvalidCommand(request.name))
        response  <- processor(request)
      } yield response
    }
  }

  type CommandRegistry = Fqcn => Option[CommandProcessor[_, _, _]]
  class DefaultCommandRegistry extends CommandRegistry {
    // TODO: improve
    import java.util.concurrent.ConcurrentHashMap
    private val registry = ConcurrentHashMap[Fqcn, CommandProcessor[_, _, _]]()

    override def apply(command: Fqcn): Option[CommandProcessor[_, _, _]] =
      Option(registry.get(command))

    def register(name: Fqcn, handler: CommandProcessor[_, _, _]): Unit =
      if (registry.putIfAbsent(name, handler) != null)
        throw IllegalStateException(s"Handler for $name is already registered")
  }

  type CommandProcessor[S, C, E] = CommandRequest => IO[CommandResponse]
  object CommandProcessor {
    def apply[S, C, E](
      commandHandler: CommandHandler[S, C, E],
      commandDeserializer: CommandDeserializer[C],
      eventSerializer: EventSerializer[E],
      resourceLoader: ResourceLoader[S, E],
    ): CommandProcessor[S, C, E] = { request =>
      for {
        c      <- commandDeserializer(request.command)
        verSt  <- resourceLoader(request.id)
        events <- commandHandler(verSt.state, c)
        ses    <- events.map(e => eventSerializer(e)).traverse(e => e)
      } yield CommandResponse(
        request.id,
        ses.zipWithIndex.map { case (se, i) => VersionedEvent(verSt.version + i + 1, se) }
      )
    }
  }

  // event store
  type EventHandler[S, E] = (S, E) => S

  /** true on success; false on conflict */
  type EventWriter = CommandResponse => IO[Boolean]
  type EventReader = ResourceId => Stream[IO, VersionedEvent]
  trait EventRepository {
    val writer: EventWriter
    val reader: EventReader
  }

  type ResourceLoader[S, E] = ResourceId => IO[VersionedState[S]]
  object ResourceLoader {
    def apply[S, C, E](
      emptyState: S,
      eventReader: EventReader,
      eventHandler: EventHandler[S, E],
      eventDeserializer: EventDeserializer[E],
    ): ResourceLoader[S, E] = { resourceId =>
      eventReader(resourceId).compile
        .fold(IO.pure(VersionedState(0, emptyState))) { (prevState, e) =>
          for {
            prev  <- prevState
            event <- eventDeserializer(e.event)
            next  <- IO.pure(eventHandler(prev.state, event))
          } yield VersionedState(prev.version + 1, next)
        }
        .flatten
    }
  }

  // exceptions
  sealed class EsException(message: String, cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  object EsException {
    case class InvalidCommand(name: ResourceName, cause: Option[Throwable] = None)
        extends EsException(s"Invalid Command: $name", cause)

    case class EventStoreFailure(cause: Throwable) extends EsException("Failed to store event", Some(cause))
    case class EventLoadFailure(cause: Throwable)  extends EsException("Failed to load event", Some(cause))
  }

  // factory
  def createCommandProcessor[S, C, E](
    emptyState: S,
    commandHandler: CommandHandler[S, C, E],
    eventHandler: EventHandler[S, E],
    commandDeserializer: CommandDeserializer[C],
    eventSerializer: EventSerializer[E],
    eventDeserializer: EventDeserializer[E],
    eventReader: EventReader,
  ): CommandProcessor[S, C, E] =
    CommandProcessor(
      commandHandler,
      commandDeserializer,
      eventSerializer,
      ResourceLoader(emptyState, eventReader, eventHandler, eventDeserializer),
    )
}
