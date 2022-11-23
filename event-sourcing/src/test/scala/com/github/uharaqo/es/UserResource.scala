package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.json.JsonCodec

class UserResource(private val eventRepository: EventRepository) {
  import UserResource.*

  private val commandHandler: CommandHandler[User, UserCommand, UserEvent] = { (s, c, ctx) =>
    import ctx.*
    s match
      case User.EMPTY =>
        c match
          case RegisterUser(name) =>
            save(UserRegistered(name))

          case _ =>
            fail(IllegalStateException("User not found"))

      case User(name, point) =>
        c match
          case RegisterUser(name) =>
            fail(IllegalStateException("Already registered"))

          case AddPoint(point) =>
            save(PointAdded(point))

          case SendPoint(recipientId, point) =>
            if (s.point < point) //
              fail(IllegalStateException("Point Shortage"))
            else
              val senderId = ctx.id.id
              for
                sent <- save(PointSent(recipientId, point))
                received <- withState(UserResource.info, recipientId) { (s, ctx2) =>
                  ctx2.save(PointReceived(senderId, point))
                }
              yield sent ++ received
  }

  val commandProcessor: CommandProcessor[User, UserCommand, UserEvent] =
    CommandProcessor(
      info,
      debug(commandHandler),
      debug(StateProvider(eventRepository.reader)),
      eventRepository,
    )
}

object UserResource {
  // commands
  sealed trait UserCommand
  case class RegisterUser(name: String)                 extends UserCommand
  case class AddPoint(point: Int)                       extends UserCommand
  case class SendPoint(recipientId: String, point: Int) extends UserCommand

  // events
  sealed trait UserEvent
  case class UserRegistered(name: String)                extends UserEvent
  case class PointAdded(point: Int)                      extends UserEvent
  case class PointSent(recipientId: String, point: Int)  extends UserEvent
  case class PointReceived(senderId: String, point: Int) extends UserEvent

  // state
  case class User(name: String, point: Int)
  object User:
    val EMPTY = User("", 0)

  import com.github.plokhotnyuk.jsoniter_scala.macros._
  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonCodec => _, _}
  val commandSerializer: JsonValueCodec[UserCommand] = JsonCodecMaker.make
  val commandDeserializers: Map[Fqcn, CommandDeserializer[UserCommand]] = {
    def deserializer[C](c: Class[C])(implicit codec: JsonValueCodec[C]): (Fqcn, CommandDeserializer[C]) =
      (c.getCanonicalName().nn, JsonCodec[C]().decode)
    Map(
      deserializer(classOf[RegisterUser])(JsonCodecMaker.make),
      deserializer(classOf[AddPoint])(JsonCodecMaker.make),
      deserializer(classOf[SendPoint])(JsonCodecMaker.make),
    )
  }

  private val eventHandler: EventHandler[User, UserEvent] = { (s, e) =>
    e match
      case UserRegistered(name) =>
        s match
          case User.EMPTY => User(name, 0)
          case _          => throw EsException.UnexpectedException

      case PointAdded(point) =>
        s.copy(point = s.point + point)

      case PointSent(recipientId, point) =>
        s.copy(point = s.point - point)

      case PointReceived(senderId, point) =>
        s.copy(point = s.point + point)
  }

  val info: ResourceInfo[User, UserEvent] = {
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    import com.github.plokhotnyuk.jsoniter_scala.core._
    implicit val codec = JsonCodecMaker.make[UserEvent]
    val eCodec         = JsonCodec()

    ResourceInfo("user", User.EMPTY, eCodec.encode(_), eCodec.decode(_), eventHandler)
  }

  private def debug[S, C, E](commandHandler: CommandHandler[S, C, E]): CommandHandler[S, C, E] =
    (s, c, ctx) =>
      for
        _ <- IO.println(s"Command: $c")
        r <- commandHandler(s, c, ctx)
        _ <- IO.println(s"Response: $r")
      yield r

  private def debug(stateProvider: StateProvider): StateProvider =
    new StateProvider {
      override def get[S, E](info: ResourceInfo[S, E], id: ResourceIdentifier): IO[VersionedState[S]] =
        for
          s <- stateProvider.get(info, id)
          _ <- IO.println(s"State: $s")
        yield s
    }
}
