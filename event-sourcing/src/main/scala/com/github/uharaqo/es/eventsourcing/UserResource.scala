package com.github.uharaqo.es.eventsourcing

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.json.JsonCodec

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

  import io.circe.Decoder, io.circe.syntax.*, io.circe.generic.auto.*, cats.syntax.functor.*
  private val cDecoder = JsonCodec.Builder(List.empty[Decoder[UserCommand]]).witha[RegisterUser].build
  private val eCodec   = JsonCodec[UserEvent]()

  def newUserCommandProcessor(eventReader: EventReader): CommandProcessor[User, UserCommand, UserEvent] =
    createCommandProcessor(
      User(""),
      commandHandler,
      eventHandler,
      cDecoder(_),
      eCodec.encode(_),
      eCodec.decode(_),
      eventReader,
    )
}
