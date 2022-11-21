package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.json.JsonCodec

object UserResource {
  // commands
  sealed trait UserCommand
  case class RegisterUser(name: String) extends UserCommand
  case class AddPoint(point: Int)       extends UserCommand
  // case class SendPoint(recipientId: String, point: Int) extends UserCommand

  // events
  sealed trait UserEvent
  case class UserRegistered(name: String) extends UserEvent
  case class PointAdded(point: Int)       extends UserEvent
  // case class PointSent(recipientId: String, point: Int)  extends UserEvent
  // case class PointReceived(senderId: String, point: Int) extends UserEvent

  // state
  case class User(name: String, point: Int)
  object User {
    val EMPTY = User("", 0)
  }

  def getCommandRegistry(
    commandProcessor: CommandProcessor[_, UserCommand, _]
  ): Either[EsException, CommandRegistry] = {
    import _root_.io.circe.Decoder, _root_.io.circe.generic.auto.*
    for {
      b <- CommandRegistry.forProcessor(commandProcessor)
      // TODO: maybe these children can be listed up by a macro
      b <- b.register(classOf[RegisterUser])
      b <- b.register(classOf[AddPoint])
      // b <- b.register(classOf[SendPoint])
    } yield b.build
  }

  private val commandHandler: CommandHandler[User, UserCommand, UserEvent] = { (s, c) =>
    if (s == User.EMPTY && !c.isInstanceOf[RegisterUser]) {
      throw IllegalStateException("User not found")
    }

    IO(
      c match {
        case RegisterUser(name) =>
          s match
            case User.EMPTY => Seq(UserRegistered(name))
            case _          => throw IllegalStateException("Already registered")
        case AddPoint(point) => Seq(PointAdded(point))
        // case SendPoint(recipientId, point) =>
        //   if (s.point >= point)
        //     Seq(PointSent(recipientId, point), PointReceived(s.name, point))
        //   else
        //     throw IllegalStateException("Point Shortage")
      }
    )
  }

  private val eventHandler: EventHandler[User, UserEvent] = { (s, e) =>
    e match {
      case UserRegistered(name) =>
        s match
          case User.EMPTY => User(name, 0)
          case _          => s // This shouoldn't happen

      case PointAdded(point) =>
        s.copy(point = s.point + point)

      // case PointSent(recipientId, point) =>
      //   s.copy(point = s.point - point)

      // case PointReceived(senderId, point) =>
      //   s
    }
  }

  import _root_.io.circe.Decoder, _root_.io.circe.generic.auto.*
  private val eCodec = JsonCodec[UserEvent]()

  def newUserCommandProcessor(eventRepository: EventRepository): CommandProcessor[User, UserCommand, UserEvent] =
    createCommandProcessor(
      User.EMPTY,
      commandHandler,
      eventHandler,
      eCodec.encode(_),
      eCodec.decode(_),
      eventRepository.reader,
      eventRepository.writer,
    )
}
