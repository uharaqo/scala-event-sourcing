package io.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import io.github.uharaqo.es.*
import io.github.uharaqo.es.Metadata.Key.StringKey
import io.github.uharaqo.es.example.UserAggregate.Dependencies
import io.github.uharaqo.es.example.proto.*
import io.github.uharaqo.es.grpc.codec.{JsonCodec, PbCodec}
import io.github.uharaqo.es.grpc.server.save

object UserAggregate {

  implicit val eventMapper: UserEvent => UserEventMessage       = PbCodec.toPbMessage
  implicit val commandMapper: UserCommand => UserCommandMessage = PbCodec.toPbMessage

  implicit val eventCodec: Codec[UserEventMessage] =
    JsonCodec[UserEventMessage]
//    PbCodec[UserEventMessage]

  lazy val stateInfo = StateInfo("user", User.EMPTY, eventCodec, eventHandler)

  lazy val commandInfo = (deps: Dependencies) =>
    CommandInfo(
      fqcn = UserCommandMessage.scalaDescriptor.fullName,
      deserializer = PbCodec[UserCommandMessage],
      debug(commandHandler(deps))
    )

  // state
  case class User(name: String, point: Int)

  object User:
    val EMPTY = User("", 0)

  // command handlers
  lazy val commandHandler: Dependencies => CommandHandler[UserCommandMessage, User, UserEventMessage] =
    for handlers <- Seq(registerUser, addPoint, sendPoint).traverse(identity)
    yield PartialCommandHandler.toCommandHandler(handlers, _.toUserCommand)

  private val hf = PartialCommandHandler.handlerFactory[UserCommand, User, UserEventMessage]

  private val registerUser = (d: Dependencies) =>
    hf.handlerFor[RegisterUser] { (c, s, ctx) =>
      s match
        case User.EMPTY => ctx.save(UserRegistered(c.name))
        case _: User    => ctx.fail(IllegalStateException("Already registered"))
    }

  private val addPoint = (d: Dependencies) =>
    hf.handlerFor[AddPoint] { (c, s, ctx) =>
      s match
        case User.EMPTY => ctx.fail(IllegalStateException("User not found"))
        case _: User    => ctx.save(PointAdded(c.point))
    }

  private val sendPoint = (d: Dependencies) =>
    hf.handlerFor[SendPoint] { (c, s, ctx) =>
      s match
        case User.EMPTY => ctx.fail(IllegalStateException("User not found"))
        case s: User =>
          if s.point < c.point then ctx.fail(IllegalStateException("Point Shortage"))
          else
            val senderId = ctx.id
            for
              sent <- ctx.save(PointSent(c.recipientId, c.point))
              received <- ctx.withState(ctx.info, c.recipientId) >>= { (s2, ctx2) =>
                if s2 == User.EMPTY then ctx2.fail(IllegalStateException("User not found"))
                else ctx2.save(PointReceived(senderId, c.point))
              }
            yield sent ++ received
    }

  // event handler
  lazy val eventHandler: EventHandler[User, UserEventMessage] = { (s, e) =>
    e.toUserEvent.asNonEmpty.get match
      case e: UserRegistered =>
        s match
          case User.EMPTY => User(e.name, 0).some
          case _          => throw EsException.UnexpectedException

      case PointAdded(point, unknownFields) =>
        s.copy(point = s.point + point).some

      case PointSent(recipientId, point, unknownFields) =>
        s.copy(point = s.point - point).some

      case PointReceived(senderId, point, unknownFields) =>
        s.copy(point = s.point + point).some
  }

  // dependencies
  trait Dependencies {}
}
