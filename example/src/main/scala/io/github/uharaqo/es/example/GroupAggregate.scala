package io.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import io.github.uharaqo.es.*
import io.github.uharaqo.es.example.proto.*
import io.github.uharaqo.es.grpc.codec.{JsonCodec, PbCodec}
import io.github.uharaqo.es.grpc.server.save

object GroupAggregate {

  private val h = AggregateHelper[GroupCommand, Group, GroupEventMessage, Dependencies]

  implicit val eventMapper: GroupEvent => GroupEventMessage       = PbCodec.toPbMessage
  implicit val commandMapper: GroupCommand => GroupCommandMessage = PbCodec.toPbMessage

  implicit val eventCodec: Codec[GroupEventMessage] =
    JsonCodec[GroupEventMessage]
//      PbCodec[GroupEventMessage]

  lazy val stateInfo = StateInfo("group", Group.EMPTY, eventCodec, eventHandler)

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
  private lazy val commandHandler =
    h.toCommandHandler[GroupCommandMessage](_.toGroupCommand, createGroup, addUser)

  private val createGroup =
    h.handlerFor[CreateGroup] { d => (c, s, ctx) =>
      s match
        case Group.EMPTY =>
          ctx.withState(UserAggregate.stateInfo, c.ownerId) >>= { (s2, ctx2) =>
            if s2 == UserAggregate.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
            else ctx.save(GroupCreated(c.ownerId, c.name))
          }
        case _ =>
          ctx.fail(IllegalStateException("Already exists"))
    }

  private val addUser =
    h.handlerFor[AddUser] { d => (c, s, ctx) =>
      s match
        case Group.EMPTY =>
          ctx.fail(IllegalStateException("Group not found"))

        case Group(ownerId, name, users) =>
          if users.contains(c.userId) then ctx.fail(IllegalStateException("Already a member"))
          else
            ctx.withState(UserAggregate.stateInfo, c.userId) >>= { (s2, ctx2) =>
              if s2 == UserAggregate.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
              else ctx.save(UserAdded(c.userId))
            }
    }

  // event handler
  private val eventHandler = h.eventHandler(_.toGroupEvent.asNonEmpty) { (s, e) =>
    e match
      case e: GroupCreated =>
        s match
          case Group.EMPTY => Group(e.ownerId, e.name, Set(e.ownerId)).some
          case _           => None

      case e: UserAdded =>
        s.copy(users = s.users + e.userId).some
  }

  // dependencies
  trait Dependencies {}
}
