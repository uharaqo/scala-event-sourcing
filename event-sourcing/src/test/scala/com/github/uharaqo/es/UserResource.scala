package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.*
import com.github.uharaqo.es.example.proto.user.*
import com.github.uharaqo.es.impl.codec.JsonCodec

object UserResource {

  import UserResource.*

//  // commands
//  sealed trait UserCommand
//  case class RegisterUser(name: String) extends UserCommand
//  case class AddPoint(point: Int) extends UserCommand
//  case class SendPoint(recipientId: String, point: Int) extends UserCommand
//
//  // events
//  sealed trait UserEvent
//  case class UserRegistered(name: String) extends UserEvent
//  case class PointAdded(point: Int) extends UserEvent
//  case class PointSent(recipientId: String, point: Int) extends UserEvent
//  case class PointReceived(senderId: String, point: Int) extends UserEvent

  // state
  case class User(name: String, point: Int)

  object User:
    val EMPTY = User("", 0)

  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonCodec as _, *}
  import com.github.plokhotnyuk.jsoniter_scala.macros.*

  /** for testing */
  val commandSerializer: Serializer[UserCommand] = c => IO(c.asMessage.toByteArray)
  val deserializers: Map[Fqcn, Deserializer[UserCommand]] = {
    def deserializer[C](c: Class[C]): (Fqcn, Deserializer[C]) =
      (c.getCanonicalName().nn, bs => IO(UserCommandMessage.parseFrom(bs).toUserCommand.asNonEmpty.get.asInstanceOf[C]))

    Seq(
      classOf[RegisterUser],
      classOf[AddPoint],
      classOf[SendPoint],
    ).map(deserializer).toMap
  }
//  val commandSerializer: Serializer[UserCommand] = {
//    val serializer: JsonValueCodec[UserCommand] = JsonCodecMaker.make
//    c => IO(writeToArray(c)(serializer))
//  }
//
//  val deserializers: Map[Fqcn, Deserializer[UserCommand]] = {
//    def deserializer[C](c: Class[C])(implicit codec: JsonValueCodec[C]): (Fqcn, Deserializer[C]) =
//      (c.getCanonicalName().nn, JsonCodec[C]().deserializer)
//
//    Map(
//      deserializer(classOf[RegisterUser])(JsonCodecMaker.make),
//      deserializer(classOf[AddPoint])(JsonCodecMaker.make),
//      deserializer(classOf[SendPoint])(JsonCodecMaker.make),
//    )
//  }

  private val eventHandler: EventHandler[User, UserEvent] = { (s, e) =>
    e.asNonEmpty.get match
      case UserRegistered(name, unknownFields) =>
        s match
          case User.EMPTY => User(name, 0)
          case _          => throw EsException.UnexpectedException

      case PointAdded(point, unknownFields) =>
        s.copy(point = s.point + point)

      case PointSent(recipientId, point, unknownFields) =>
        s.copy(point = s.point - point)

      case PointReceived(senderId, point, unknownFields) =>
        s.copy(point = s.point + point)
  }

  private val commandHandler: CommandHandler[User, UserCommand, UserEvent] = { (s, c, ctx) =>
    import ctx.*
    s match
      case User.EMPTY =>
        c.asNonEmpty.get match
          case RegisterUser(name, unknownFields) =>
            save(UserRegistered(name))

          case _ =>
            fail(IllegalStateException("User not found"))

      case User(name, point) =>
        c.asNonEmpty.get match
          case RegisterUser(name, unknownFields) =>
            fail(IllegalStateException("Already registered"))

          case AddPoint(point, unknownFields) =>
            save(PointAdded(point))

          case SendPoint(recipientId, point, unknownFields) =>
            if (s.point < point) //
              fail(IllegalStateException("Point Shortage"))
            else
              val senderId = ctx.id
              for
                sent <- save(PointSent(recipientId, point))
                received <- withState(UserResource.info, recipientId) { (s, ctx2) =>
                  if (s == User.EMPTY)
                    ctx.fail(IllegalStateException("User not found"))
                  else
                    ctx2.save(PointReceived(senderId, point))
                }
              yield sent ++ received
  }

  val info: StateInfo[User, UserEvent] = {
//    import com.github.plokhotnyuk.jsoniter_scala.core.*
//    import com.github.plokhotnyuk.jsoniter_scala.macros.*
//    implicit val codec = JsonCodecMaker.make[UserEvent]
//    val eventCodec     = JsonCodec()
    val serializer: Serializer[UserEvent]     = o => IO(o.asMessage.toByteArray)
    val deserializer: Deserializer[UserEvent] = bs => IO(UserEventMessage.parseFrom(bs).toUserEvent)

    StateInfo("user", User.EMPTY, serializer, deserializer, eventHandler)
  }

  def newCommandRegistry(): CommandRegistry =
    CommandRegistry(info, deserializers, debug(commandHandler))
}
