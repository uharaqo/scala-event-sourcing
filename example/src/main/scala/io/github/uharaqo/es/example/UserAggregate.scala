package io.github.uharaqo.es.example

import cats.implicits.*
import io.github.uharaqo.es.*
import io.github.uharaqo.es.example.proto.*
import io.github.uharaqo.es.grpc.codec.{JsonCodec, PbCodec}
import io.github.uharaqo.es.grpc.server.save

object UserAggregate {

  private val h  = AggregateHelper[UserCommandMessage, User, UserEventMessage, Dependencies]
  private val h2 = h.convert[UserCommand](_.toUserCommand)

  implicit val eventMapper: UserEvent => UserEventMessage       = PbCodec.toPbMessage
  implicit val commandMapper: UserCommand => UserCommandMessage = PbCodec.toPbMessage
  implicit val eventCodec: Codec[UserEventMessage] = JsonCodec[UserEventMessage] //    PbCodec[UserEventMessage]

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
  private lazy val commandHandler = h2.commandHandler(registerUser, addPoint, sendPoint)

  private val registerUser =
    h2.commandHandlerFor[RegisterUser] { d => (c, s, ctx) =>
      s match
        case User.EMPTY => ctx.save(UserRegistered(c.name))
        case _: User    => ctx.fail(IllegalStateException("Already registered"))
    }

  private val addPoint =
    h2.commandHandlerFor[AddPoint] { d => (c, s, ctx) =>
      s match
        case User.EMPTY => ctx.fail(IllegalStateException("User not found"))
        case _: User    => ctx.save(ProductTypes.convert[AddPoint, PointAdded](c))
    }

  private val sendPoint =
    h2.commandHandlerFor[SendPoint] { d => (c, s, ctx) =>
      s match
        case User.EMPTY => ctx.fail(IllegalStateException("User not found"))
        case s: User =>
          if s.point < c.point then ctx.fail(IllegalStateException("Point Shortage"))
          else
            val senderId = ctx.id
            for
              sent <- ctx.save(ProductTypes.convert[SendPoint, PointSent](c))
              received <- ctx.withState(ctx.info, c.recipientId) >>= { (s2, ctx2) =>
                if s2 == User.EMPTY then ctx2.fail(IllegalStateException("User not found"))
                else ctx2.save(PointReceived(senderId, c.point))
              }
            yield sent ++ received
    }

  // event handler
  private val eventHandler = h.eventHandler { (s, e) =>
    e.toUserEvent match
      case e: UserRegistered =>
        s match
          case User.EMPTY => User(e.name, 0)
          case _          => throw EsException.UnexpectedException

      case e: PointAdded    => s.copy(point = s.point + e.point)
      case e: PointSent     => s.copy(point = s.point - e.point)
      case e: PointReceived => s.copy(point = s.point + e.point)
      case _                => throw EsException.UnexpectedException
  }

  // dependencies
  trait Dependencies {}
}
