package com.github.uharaqo.es.eventsourcing

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import fs2.Stream

import java.time.Instant
import io.circe.Decoder
import javax.swing.tree.DefaultMutableTreeNode
import com.github.uharaqo.es.io.json.JsonCodec

object EventSourcing {

  type ResourceName       = String
  type ResourceIdentifier = String
  type Version            = Long
  type Fqcn               = String

  case class ResourceId(name: ResourceName, id: ResourceIdentifier)
  case class VersionedEvent(version: Version, event: Serialized)
  case class VersionedState[S](version: Version, state: S)

  // serializers
  type RawCommand = String
  type Serialized = String

  type CommandDeserializer[C] = RawCommand => IO[C]
  type EventSerializer[E]     = E => IO[Serialized]
  type EventDeserializer[E]   = Serialized => IO[E]

  // command handler
  type CommandHandler[S, C, E] = (S, C) => IO[Seq[E]]

  case class CommandRequest(id: ResourceId, name: Fqcn, command: RawCommand)
  case class CommandResponse(id: ResourceId, events: Seq[VersionedEvent])

  type CommandDispatcher = CommandRequest => IO[CommandResponse]
  object CommandDispatcher {
    def apply(commandRegistry: CommandRegistry): CommandDispatcher = { request =>
      for {
        info     <- IO.fromOption(commandRegistry(request.name))(EsException.InvalidCommand(request.name))
        response <- info.processor(request, info.deserializer)
      } yield response
    }
  }

  case class CommandInfo[C](
    fqcn: Fqcn,
    deserializer: CommandDeserializer[C],
    processor: CommandProcessor[_, C, _],
  )

  type CommandRegistry = Fqcn => Option[CommandInfo[?]]
  object CommandRegistry {
    def forProcessor[C](processor: CommandProcessor[_, C, _]): Either[EsException, Builder[C]] =
      Right(DefaultBuilder[C](processor, Map.empty))

    trait Builder[C] {
      def register[CC <: C](clazz: Class[CC])(implicit decoder: Decoder[CC]): Either[EsException, Builder[C]]

      def build: CommandRegistry
    }
    private class DefaultBuilder[C](
      private val processor: CommandProcessor[_, C, _],
      private val map: Map[Fqcn, CommandInfo[C]],
    ) extends Builder[C] {
      override def register[CC <: C](clazz: Class[CC])(implicit decoder: Decoder[CC]): Either[EsException, Builder[C]] =
        val fqcn = clazz.getCanonicalName
        if (map.contains(fqcn))
          Left(EsException.CommandAlreadyRegistered(fqcn))
        else
          Right(
            DefaultBuilder(
              processor,
              map + (fqcn -> CommandInfo(fqcn, JsonCodec.getDecoder[CC](), processor))
            )
          )

      override def build: CommandRegistry = fqcn => map.get(fqcn)
    }
  }

  type CommandProcessor[S, C, E] = (CommandRequest, CommandDeserializer[C]) => IO[CommandResponse]
  object CommandProcessor {
    def apply[S, C, E](
      commandHandler: CommandHandler[S, C, E],
      eventSerializer: EventSerializer[E],
      resourceLoader: ResourceLoader[S, E],
      eventWriter: EventWriter,
    ): CommandProcessor[S, C, E] = { (request, deserializer) =>
      for {
        c     <- deserializer(request.command)
        verSt <- resourceLoader(request.id)
        // TODO: remove this
        _ <- IO.println(s"State: $verSt")
        events <-
          try commandHandler(verSt.state, c)
          catch { case e => IO.raiseError(EsException.CommandHandlerFailure(e)) }
        ses     <- events.map(e => eventSerializer(e)).traverse(e => e)
        res     <- IO.pure(toResponse(request.id, verSt.version, ses))
        success <- eventWriter(res)
        // TODO: retry?
        _ <- if (!success) IO.raiseError(EsException.EventStoreConflict()) else IO.unit
      } yield res
    // TODO: convert error into a response
    // .recover {
    //   case EsException.CommandHandlerFailure => toResponse(prevVersion)
    // }
    }

    private def toResponse(id: ResourceId, prevVersion: Long, binaries: Seq[Serialized]): CommandResponse = {
      val versionedEvents =
        binaries.zipWithIndex.map { case (se, i) => VersionedEvent(prevVersion + i + 1, se) }
      CommandResponse(id, versionedEvents)
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

    case class EventStoreFailure(t: Throwable)      extends EsException("Failed to store event", Some(t))
    case class EventStoreConflict()                 extends EsException("Failed to store event due to conflict", None)
    case class EventLoadFailure(t: Throwable)       extends EsException("Failed to load event", Some(t))
    case class CommandAlreadyRegistered(fqcn: Fqcn) extends EsException(s"Command already registered: $fqcn", None)
    case class CommandHandlerFailure(t: Throwable)  extends EsException(s"Command handler failure", Some(t))
  }

  // factory
  def createCommandProcessor[S, C, E](
    emptyState: S,
    commandHandler: CommandHandler[S, C, E],
    eventHandler: EventHandler[S, E],
    eventSerializer: EventSerializer[E],
    eventDeserializer: EventDeserializer[E],
    eventReader: EventReader,
    eventWriter: EventWriter,
  ): CommandProcessor[S, C, E] =
    CommandProcessor(
      commandHandler,
      eventSerializer,
      ResourceLoader(emptyState, eventReader, eventHandler, eventDeserializer),
      eventWriter,
    )
}
