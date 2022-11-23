package com.github.uharaqo.es.eventsourcing

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.json.JsonCodec
import fs2.Stream

import java.time.Instant

object EventSourcing {

  type ResourceName       = String
  type ResourceIdentifier = String
  type Version            = Long
  type Fqcn               = String
  type RawCommand         = String
  type Serialized         = String

  case class ResourceId(name: ResourceName, id: ResourceIdentifier)
  case class VersionedEvent(version: Version, event: Serialized)
  case class VersionedState[S](version: Version, state: S)

  // serializers
  type CommandDeserializer[C] = RawCommand => IO[C]
  type EventSerializer[E]     = E => IO[Serialized]
  type EventDeserializer[E]   = Serialized => IO[E]

  // event store
  type EventHandler[S, E] = (S, E) => S

  case class ResourceInfo[S, E](
    name: ResourceName,
    emptyState: S,
    eventSerializer: EventSerializer[E],
    eventDeserializer: EventDeserializer[E],
    eventHandler: EventHandler[S, E],
  )

  /** returns true on success; false on conflict */
  type EventWriter = Seq[CommandResponse] => IO[Boolean]
  type EventReader = ResourceId => Stream[IO, VersionedEvent]
  trait EventRepository {
    val writer: EventWriter
    val reader: EventReader
  }

  trait StateProvider {
    def get[S, E](info: ResourceInfo[S, E], id: ResourceIdentifier): IO[VersionedState[S]]
  }
  object StateProvider {
    def apply[S, C, E](eventReader: EventReader): StateProvider = new StateProvider {
      override def get[S, E](info: ResourceInfo[S, E], id: ResourceIdentifier): IO[VersionedState[S]] =
        eventReader(ResourceId(info.name, id)).compile
          .fold(IO.pure(VersionedState(0, info.emptyState))) { (prevState, e) =>
            for {
              prev  <- prevState
              event <- info.eventDeserializer(e.event)
              next  <- IO.pure(info.eventHandler(prev.state, event))
            } yield VersionedState(prev.version + 1, next)
          }
          .flatten
    }
  }

  // command handler
  case class CommandRequest(id: ResourceId, name: Fqcn, command: RawCommand)
  case class CommandResponse(id: ResourceId, event: VersionedEvent)

  type CommandHandler[S, C, E] = (S, C, CommandHandlerContext[E]) => IO[Seq[CommandResponse]]

  trait CommandHandlerContext[E] {
    val id: ResourceId
    val prevVer: Version
    val serializer: EventSerializer[E]
    val stateProvider: StateProvider
    def save(events: E*): IO[Seq[CommandResponse]]
    def fail(e: Exception): IO[Seq[CommandResponse]] = IO.raiseError(e)
    def externalEvents[S2, E2](info: ResourceInfo[S2, E2], id: ResourceIdentifier)(
      handler: (S2, CommandHandlerContext[E2]) => IO[Seq[CommandResponse]]
    ): IO[Seq[CommandResponse]]
  }
  class DefaultCommandHandlerContext[E](
    override val id: ResourceId,
    override val prevVer: Version,
    override val serializer: EventSerializer[E],
    override val stateProvider: StateProvider,
  ) extends CommandHandlerContext[E] {

    override def save(events: E*): IO[Seq[CommandResponse]] =
      events.zipWithIndex.traverse {
        case (e, i) =>
          serializer(e).map(se => CommandResponse(id, VersionedEvent(prevVer + i + 1, se)))
      }

    override def externalEvents[S2, E2](info: ResourceInfo[S2, E2], id: ResourceIdentifier)(
      handler: (S2, CommandHandlerContext[E2]) => IO[Seq[CommandResponse]]
    ): IO[Seq[CommandResponse]] = {
      val resourceId = ResourceId(info.name, id)
      for {
        verS <- stateProvider.get(info, id)
        es <- handler(
          verS.state,
          new DefaultCommandHandlerContext[E2](resourceId, verS.version, info.eventSerializer, stateProvider)
        )
      } yield es
    }
  }

  type CommandDispatcher = CommandRequest => IO[Seq[CommandResponse]]
  object CommandDispatcher {
    def apply(commandRegistry: CommandRegistry): CommandDispatcher = { request =>
      for {
        info      <- IO.fromOption(commandRegistry(request.name))(EsException.InvalidCommand(request.name))
        responses <- info.processor(request, info.deserializer)
      } yield responses
    }
  }

  case class CommandInfo[C](
    fqcn: Fqcn,
    deserializer: CommandDeserializer[C],
    processor: CommandProcessor[_, C, _],
  )

  type CommandRegistry = Fqcn => Option[CommandInfo[?]]
  object CommandRegistry {
    def from[C](
      processor: CommandProcessor[_, C, _],
      deserializers: Map[Fqcn, CommandDeserializer[C]]
    ): CommandRegistry = {
      val map = deserializers.map { case (k, v) => (k, CommandInfo[C](k, v, processor)) }
      map.get
    }
  }

  type CommandProcessor[S, C, E] = (CommandRequest, CommandDeserializer[C]) => IO[Seq[CommandResponse]]
  object CommandProcessor {
    def apply[S, C, E](
      info: ResourceInfo[S, E],
      commandHandler: CommandHandler[S, C, E],
      stateProvider: StateProvider,
      eventRepository: EventRepository,
    ): CommandProcessor[S, C, E] = { (request, deserializer) =>
      for {
        c    <- deserializer(request.command)
        verS <- stateProvider.get(info, request.id.id)
        ctx = new DefaultCommandHandlerContext(request.id, verS.version, info.eventSerializer, stateProvider)
        ress <-
          try
            commandHandler(verS.state, c, ctx)
              .recoverWith(t => IO.raiseError(EsException.CommandHandlerFailure(t)))
          catch { case e: Throwable => IO.raiseError(EsException.CommandHandlerFailure(e)) }
        success <- eventRepository.writer(ress)
        // TODO: retry?
        _ <- if (!success) IO.raiseError(EsException.EventStoreConflict()) else IO.unit
      } yield ress
    }
  }

  // exceptions
  sealed class EsException(message: String, cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  object EsException {
    case class InvalidCommand(name: ResourceName, cause: Option[Throwable] = None)
        extends EsException(s"Invalid Command: $name", cause)

    case class EventStoreFailure(t: Throwable)      extends EsException("Failed to store event", Some(t))
    case class EventStoreConflict()                 extends EsException("Failed to store event due to conflict", None)
    case class EventLoadFailure(t: Throwable)       extends EsException("Failed to load event", Some(t))
    case class CommandAlreadyRegistered(fqcn: Fqcn) extends EsException(s"Command already registered: $fqcn", None)
    case class CommandHandlerFailure(t: Throwable)  extends EsException(s"Command handler failure", Some(t))
    case class UnknownException(t: Throwable)       extends EsException(s"Unknown Exception", Some(t))
  }
}
