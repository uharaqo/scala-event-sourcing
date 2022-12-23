package io.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import io.github.uharaqo.es.*
import io.github.uharaqo.es.grpc.codec.PbCodec
import io.github.uharaqo.es.grpc.server.save
import io.github.uharaqo.es.proto.example.*
import io.github.uharaqo.es.proto.example.UserEvent.Empty

object GroupResource {

  type GroupCommandHandler = PartialCommandHandler[Group, GroupCommand, GroupEventMessage]
  implicit val eventMapper: GroupEvent => GroupEventMessage       = PbCodec.toPbMessage(_)
  implicit val commandMapper: GroupCommand => GroupCommandMessage = PbCodec.toPbMessage(_)

  val stateInfo =
    StateInfo(
      "group",
      Group.EMPTY,
      PbCodec[GroupEventMessage],
      eventHandler,
    )
  val commandInfo = (deps: Dependencies) =>
    CommandInfo(
      GroupCommandMessage.scalaDescriptor.fullName,
      PbCodec[GroupCommandMessage],
      debug(commandHandler(deps))
    )

  // state
  case class Group(ownerId: String, name: String, users: Set[String])

  object Group:
    val EMPTY = Group("", "", Set.empty)

  // command handlers
  lazy val commandHandler: Dependencies => CommandHandler[Group, GroupCommandMessage, GroupEventMessage] =
    Seq(createGroup, addUser)
      .traverse(identity)
      .andThen(PartialCommandHandler.toCommandHandler(_, _.toGroupCommand))

  private val createGroup: Dependencies => GroupCommandHandler = deps => { (s, ctx) =>
    {
      case c: CreateGroup =>
        s match
          case Group.EMPTY =>
            ctx.withState(UserResource.stateInfo, c.ownerId) { (s2, ctx2) =>
              if s2 == UserResource.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
              else ctx.save(GroupCreated(c.ownerId, c.name))
            }
          case _ =>
            ctx.fail(IllegalStateException("Already exists"))
    }
  }

  private val addUser: Dependencies => GroupCommandHandler = deps => { (s, ctx) =>
    {
      case c: AddUser =>
        s match
          case Group.EMPTY =>
            ctx.fail(IllegalStateException("Group not found"))

          case Group(ownerId, name, users) =>
            if users.contains(c.userId) then ctx.fail(IllegalStateException("Already a member"))
            else
              ctx.withState(UserResource.stateInfo, c.userId) { (s, ctx2) =>
                if s == UserResource.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
                else ctx.save(UserAdded(c.userId))
              }
    }
  }

  // event handler
  lazy val eventHandler: EventHandler[Group, GroupEventMessage] = { (s, e) =>
    e.toGroupEvent.asNonEmpty.get match
      case GroupCreated(ownerId, name, unknownFields) =>
        s match
          case Group.EMPTY => Group(ownerId, name, Set(ownerId)).some
          case _           => None

      case UserAdded(userId, unknownFields) =>
        s.copy(users = s.users + userId).some
  }

  // dependencies
  trait Dependencies {}
}
