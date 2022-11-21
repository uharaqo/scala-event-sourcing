package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.json.JsonCodec
import java.nio.charset.StandardCharsets
import com.github.uharaqo.es.io.json.CommandDecoder

object UserResource {
  sealed trait UserCommand
  case class RegisterUser(name: String) extends UserCommand

  sealed trait UserEvent
  case class UserRegistered(name: String) extends UserEvent

  case class User(name: String)

  private val eventHandler: EventHandler[User, UserEvent] = { (prevState, event) =>
    event match {
      case UserRegistered(name) => User(name)
    }
  }

  private val commandHandler: CommandHandler[User, UserCommand, UserEvent] = { (user, command) =>
    val events = command match {
      case RegisterUser(name) => Seq(UserRegistered(name))
    }
    IO.pure(events)
  }

  import _root_.io.circe.Decoder, _root_.io.circe.generic.auto.*
  private val cDecoder = CommandDecoder.forCommand[UserCommand].withCommand[RegisterUser].build
  private val eCodec   = JsonCodec[UserEvent]()

  def newUserCommandProcessor(eventReader: EventReader): CommandProcessor[User, UserCommand, UserEvent] =
    createCommandProcessor(
      User(""),
      commandHandler,
      eventHandler,
      c => cDecoder(c.getBytes(StandardCharsets.UTF_8)),
      eCodec.encode(_),
      eCodec.decode(_),
      eventReader,
    )
}
