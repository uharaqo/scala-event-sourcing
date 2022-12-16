package com.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.*
import com.github.uharaqo.es.grpc.server.{savePb, GrpcAggregateInfo}
import com.github.uharaqo.es.proto.example.*
import com.github.uharaqo.es.proto.example.UserEvent.Empty

object GroupResource {

  object GroupCommandHandler {
    def apply(h: SelectiveCommandHandler[Group, GroupCommandMessage, GroupEventMessage]) = h
  }

  lazy val info =
    GrpcAggregateInfo("group", Group.EMPTY, GroupCommandMessage.scalaDescriptor, eventHandler, commandHandler)

  implicit val eMapper: GroupEvent => GroupEventMessage     = _.asMessage
  implicit val cMapper: GroupCommand => GroupCommandMessage = _.asMessage

  // state
  case class Group(ownerId: String, name: String, users: Set[String])
  object Group:
    val EMPTY = Group("", "", Set.empty)

  private val eventHandler: EventHandler[Group, GroupEventMessage] = { (s, e) =>
    e.toGroupEvent.asNonEmpty.get match
      case GroupCreated(ownerId, name, unknownFields) =>
        s match
          case Group.EMPTY => Group(ownerId, name, Set(ownerId)).some
          case _           => None

      case UserAdded(userId, unknownFields) =>
        s.copy(users = s.users + userId).some
  }

  private val createGroup = (dep: Dependencies) =>
    GroupCommandHandler { (s, c, ctx) =>
      c.sealedValue.createGroup.map { c =>
        s match
          case Group.EMPTY =>
            ctx.withState(UserResource.info.stateInfo, c.ownerId) { (s2, ctx2) =>
              if (s2 == UserResource.User.EMPTY)
                ctx.fail(IllegalStateException("User not found"))
              else
                ctx.savePb(GroupCreated(c.ownerId, c.name))
            }
          case _ =>
            ctx.fail(IllegalStateException("Already exists"))
      }
    }

  private val addUser = (dep: Dependencies) =>
    GroupCommandHandler { (s, c, ctx) =>
      c.sealedValue.addUser.map { c =>
        s match
          case Group.EMPTY =>
            ctx.fail(IllegalStateException("Group not found"))

          case Group(ownerId, name, users) =>
            if (users.contains(c.userId))
              ctx.fail(IllegalStateException("Already a member"))
            else
              ctx.withState(UserResource.info.stateInfo, c.userId) { (s, ctx2) =>
                if (s == UserResource.User.EMPTY)
                  ctx.fail(IllegalStateException("User not found"))
                else
                  ctx.savePb(UserAdded(c.userId))
              }
      }
    }

  private val commandHandler = SelectiveCommandHandler.toCommandHandler(Seq(createGroup, addUser))

  trait Dependencies {}
}
