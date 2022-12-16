package com.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.*
import com.github.uharaqo.es.grpc.server.{savePb, GrpcAggregateInfo}
import com.github.uharaqo.es.proto.example.*

object UserResource {

  object UserCommandHandler {
    def apply(h: SelectiveCommandHandler[User, UserCommandMessage, UserEventMessage]) = h
  }

  lazy val info =
    GrpcAggregateInfo("user", User.EMPTY, UserCommandMessage.scalaDescriptor, eventHandler, commandHandler)
  implicit val eMapper: UserEvent => UserEventMessage     = _.asMessage
  implicit val cMapper: UserCommand => UserCommandMessage = _.asMessage

  // state
  case class User(name: String, point: Int)

  object User:
    val EMPTY = User("", 0)

  private val registerUser = (dep: Dependencies) =>
    UserCommandHandler { (s, c, ctx) =>
      c.sealedValue.registerUser.map { c =>
        UserCommandMessage.scalaDescriptor
        s match
          case User.EMPTY =>
            ctx.savePb(UserRegistered(c.name))

          case User(name, point) =>
            ctx.fail(IllegalStateException("Already registered"))
      }
    }

  private val addPoint = (dep: Dependencies) =>
    UserCommandHandler { (s, c, ctx) =>
      c.sealedValue.addPoint.map { c =>
        s match
          case User.EMPTY =>
            ctx.fail(IllegalStateException("User not found"))

          case User(name, point) =>
            ctx.savePb(PointAdded(c.point))
      }
    }

  private val sendPoint = (dep: Dependencies) =>
    UserCommandHandler { (s, c, ctx) =>
      c.sealedValue.sendPoint.map { c =>
        s match
          case User.EMPTY =>
            ctx.fail(IllegalStateException("User not found"))

          case User(name, point) =>
            if (point < c.point) //
              ctx.fail(IllegalStateException("Point Shortage"))
            else
              val senderId = ctx.id
              for
                sent <- ctx.savePb(PointSent(c.recipientId, c.point))
                received <- ctx.withState(ctx.info, c.recipientId) { (s2, ctx2) =>
                  if (s2 == User.EMPTY)
                    ctx2.fail(IllegalStateException("User not found"))
                  else
                    ctx2.savePb(PointReceived(senderId, c.point))
                }
              yield sent ++ received
      }
    }

  private val eventHandler: EventHandler[User, UserEventMessage] = { (s, e) =>
    e.toUserEvent.asNonEmpty.get match
      case UserRegistered(name, unknownFields) =>
        s match
          case User.EMPTY => User(name, 0).some
          case _          => throw EsException.UnexpectedException

      case PointAdded(point, unknownFields) =>
        s.copy(point = s.point + point).some

      case PointSent(recipientId, point, unknownFields) =>
        s.copy(point = s.point - point).some

      case PointReceived(senderId, point, unknownFields) =>
        s.copy(point = s.point + point).some
  }

  private val commandHandler = SelectiveCommandHandler.toCommandHandler(Seq(registerUser, addPoint, sendPoint))

  trait Dependencies {}
}
