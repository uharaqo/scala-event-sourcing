package io.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import io.github.uharaqo.es.*
import io.github.uharaqo.es.grpc.codec.PbCodec
import io.github.uharaqo.es.grpc.server.{savePb, GrpcAggregateInfo}
import io.github.uharaqo.es.proto.example.*
import io.github.uharaqo.es.proto.example.UserEvent.Empty

object GroupResource {

  type GroupCommandHandler = SelectiveCommandHandler[Group, GroupCommandMessage, GroupEventMessage]
  implicit val eMapper: GroupEvent => GroupEventMessage     = PbCodec.toPbMessage(_)
  implicit val cMapper: GroupCommand => GroupCommandMessage = PbCodec.toPbMessage(_)

  lazy val info =
    GrpcAggregateInfo(
      "group",
      Group.EMPTY,
      GroupCommandMessage.scalaDescriptor,
      eventHandler,
      (deps: Dependencies) => debug(commandHandler(deps))
    )

  // state
  case class Group(ownerId: String, name: String, users: Set[String])

  object Group:
    val EMPTY = Group("", "", Set.empty)

  private lazy val commandHandler = SelectiveCommandHandler.toCommandHandler(Seq(createGroup, addUser))

  private val createGroup: Dependencies => GroupCommandHandler = deps => { (s, c, ctx) =>
    c.sealedValue.createGroup.map { c =>
      s match
        case Group.EMPTY =>
          ctx.withState(UserResource.info.stateInfo, c.ownerId) { (s2, ctx2) =>
            if s2 == UserResource.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
            else ctx.savePb(GroupCreated(c.ownerId, c.name))
          }
        case _ =>
          ctx.fail(IllegalStateException("Already exists"))
    }
  }

  private val addUser: Dependencies => GroupCommandHandler = deps => { (s, c, ctx) =>
    c.sealedValue.addUser.map { c =>
      s match
        case Group.EMPTY =>
          ctx.fail(IllegalStateException("Group not found"))

        case Group(ownerId, name, users) =>
          if users.contains(c.userId) then ctx.fail(IllegalStateException("Already a member"))
          else
            ctx.withState(UserResource.info.stateInfo, c.userId) { (s, ctx2) =>
              if s == UserResource.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
              else ctx.savePb(UserAdded(c.userId))
            }
    }
  }

  private val eventHandler: EventHandler[Group, GroupEventMessage] = { (s, e) =>
    e.toGroupEvent.asNonEmpty.get match
      case GroupCreated(ownerId, name, unknownFields) =>
        s match
          case Group.EMPTY => Group(ownerId, name, Set(ownerId)).some
          case _           => None

      case UserAdded(userId, unknownFields) =>
        s.copy(users = s.users + userId).some
  }

  trait Dependencies {}
}
