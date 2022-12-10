package com.github.uharaqo.es.eventsourcing

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.Serde.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import fs2.Stream

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

object EventSourcing {

  type ResourceName       = String
  type ResourceIdentifier = String
  type Version            = Long
  type Fqcn               = String

  case class ResourceId(name: ResourceName, id: ResourceIdentifier)
  case class VersionedEvent(version: Version, event: Bytes)
  case class VersionedState[S](version: Version, state: S)

  // serializers
  type CommandDeserializer[C] = Deserializer[C]
  type EventSerializer[E]     = Serializer[E]
  type EventDeserializer[E]   = Deserializer[E]

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
  trait EventRepository:
    val writer: EventWriter
    val reader: EventReader

  trait StateProvider:
    def get[S, E](info: ResourceInfo[S, E], id: ResourceIdentifier): IO[VersionedState[S]]

  object StateProvider:
    def apply[S, C, E](eventReader: EventReader): StateProvider = new StateProvider {
      override def get[S, E](info: ResourceInfo[S, E], id: ResourceIdentifier): IO[VersionedState[S]] =
        eventReader(ResourceId(info.name, id)).compile
          .fold(VersionedState(0, info.emptyState).pure[IO]) { (prevState, e) =>
            for
              prev  <- prevState
              event <- info.eventDeserializer(e.event)
              next  <- info.eventHandler(prev.state, event).pure[IO]
            yield VersionedState(prev.version + 1, next)
          }
          .flatten
    }

  // command handler
  type TsMs = Long
  case class CommandRequest(id: ResourceId, name: Fqcn, command: Bytes)
  case class CommandResponse(id: ResourceId, event: VersionedEvent, timestamp: TsMs) {
    override def toString(): String =
      s"CommandResponse($id,VersionedEvent(${event.version},${String(event.event, UTF_8)}),${Instant.ofEpochMilli(timestamp)})"
  }

  type CommandHandler[S, C, E] = (S, C, CommandHandlerContext[E]) => IO[Seq[CommandResponse]]

  trait CommandHandlerContext[E]:
    val id: ResourceId
    val prevVer: Version
    val serializer: EventSerializer[E]
    val stateProvider: StateProvider

    def save(events: E*): IO[Seq[CommandResponse]]
    def withState[S2, E2](
      info: ResourceInfo[S2, E2],
      id: ResourceIdentifier
    )(handler: (S2, CommandHandlerContext[E2]) => IO[Seq[CommandResponse]]): IO[Seq[CommandResponse]]
    def fail(e: Exception): IO[Seq[CommandResponse]] = IO.raiseError(e)

  class DefaultCommandHandlerContext[E](
    override val id: ResourceId,
    override val prevVer: Version,
    override val serializer: EventSerializer[E],
    override val stateProvider: StateProvider,
  ) extends CommandHandlerContext[E] {

    override def save(events: E*): IO[Seq[CommandResponse]] =
      events.zipWithIndex.traverse {
        case (e, i) =>
          serializer(e).map { e =>
            CommandResponse(id, VersionedEvent(prevVer + i + 1, e), System.currentTimeMillis())
          }
      }

    override def withState[S2, E2](info: ResourceInfo[S2, E2], id: ResourceIdentifier)(
      handler: (S2, CommandHandlerContext[E2]) => IO[Seq[CommandResponse]]
    ): IO[Seq[CommandResponse]] = {
      val resourceId = ResourceId(info.name, id)
      for
        verS <- stateProvider.get(info, id)
        es <- handler(
          verS.state,
          new DefaultCommandHandlerContext[E2](resourceId, verS.version, info.eventSerializer, stateProvider)
        )
      yield es
    }
  }

  type CommandDispatcher = CommandRequest => IO[Seq[CommandResponse]]

  object CommandDispatcher:
    def apply(commandRegistry: CommandRegistry): CommandDispatcher = { request =>
      for
        info      <- IO.fromOption(commandRegistry.get(request.name))(EsException.InvalidCommand(request.name))
        responses <- info.processor(request, info.deserializer)
      yield responses
    }

  case class CommandInfo[C](
    fqcn: Fqcn,
    deserializer: CommandDeserializer[C],
    processor: CommandProcessor[_, C, _],
  )

  type CommandRegistry = Map[Fqcn, CommandInfo[?]]

  object CommandRegistry:
    def from[C](
      processor: CommandProcessor[_, C, _],
      deserializers: Map[Fqcn, CommandDeserializer[C]]
    ): CommandRegistry =
      deserializers.map { case (k, v) => (k, CommandInfo[C](k, v, processor)) }

  type CommandProcessor[S, C, E] = (CommandRequest, CommandDeserializer[C]) => IO[Seq[CommandResponse]]

  object CommandProcessor:
    def apply[S, C, E](
      info: ResourceInfo[S, E],
      commandHandler: CommandHandler[S, C, E],
      stateProvider: StateProvider,
      eventRepository: EventRepository,
      retryOnConflict: Boolean = true,
    ): CommandProcessor[S, C, E] = { (request, deserializer) =>
      val handle = { (command: C) =>
        for
          verS <- stateProvider.get(info, request.id.id)
          ctx = new DefaultCommandHandlerContext(request.id, verS.version, info.eventSerializer, stateProvider)
          ress <-
            commandHandler(verS.state, command, ctx)
              .recoverWith(t => IO.raiseError(EsException.CommandHandlerFailure(t)))
          success <- eventRepository.writer(ress)
          _       <- if (!success) IO.raiseError(EsException.EventStoreConflict()) else IO.unit
        yield ress
      }

      for
        c <- deserializer(request.command)
        ress <- handle(c).recoverWith {
          case t: EsException.EventStoreConflict =>
            if (retryOnConflict) handle(c) else IO.raiseError(t)
        }
      yield ress
    }

  // exceptions
  sealed class EsException(message: String, cause: Option[Throwable] = none)
      extends RuntimeException(message, cause.orNull)

  object EsException:
    case class InvalidCommand(name: ResourceName, cause: Option[Throwable] = none)
        extends EsException(s"Invalid Command: $name", cause)
    case class EventStoreFailure(t: Throwable)      extends EsException("Failed to store event", t.some)
    case class EventStoreConflict()                 extends EsException("Failed to store event due to conflict", none)
    case class EventLoadFailure(t: Throwable)       extends EsException("Failed to load event", t.some)
    case class CommandAlreadyRegistered(fqcn: Fqcn) extends EsException(s"Command already registered: $fqcn", none)
    case class CommandHandlerFailure(t: Throwable)  extends EsException(s"Command handler failure", t.some)
    case object UnexpectedException                 extends EsException(s"Unexpected Exception", none)

  // debugger TODO: improve these
  def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
    (s, c, ctx) =>
      for
        _ <- IO.println(s"Command: $c")
        r <- commandHandler(s, c, ctx)
        _ <- IO.println(s"Response: $r")
      yield r

  def debug(stateProvider: StateProvider): StateProvider =
    new StateProvider {
      override def get[S, E](info: ResourceInfo[S, E], id: ResourceIdentifier): IO[VersionedState[S]] =
        for
          s <- stateProvider.get(info, id)
          _ <- IO.println(s"State: $s")
        yield s
    }
}
