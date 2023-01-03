package io.github.uharaqo.es.example

import cats.implicits.*
import io.github.uharaqo.es.*
import io.github.uharaqo.es.example.proto.*
import io.github.uharaqo.es.grpc.codec.{JsonCodec, PbCodec}
import io.github.uharaqo.es.grpc.server.save

object GroupAggregate {

  private val h  = AggregateHelper[GroupCommandMessage, Group, GroupEventMessage, Dependencies]
  private val h2 = h.convert[GroupCommand](_.toGroupCommand)

  implicit val eventMapper: GroupEvent => GroupEventMessage       = PbCodec.toPbMessage
  implicit val commandMapper: GroupCommand => GroupCommandMessage = PbCodec.toPbMessage
  implicit val eventCodec: Codec[GroupEventMessage] = JsonCodec[GroupEventMessage] //      PbCodec[GroupEventMessage]

  lazy val stateInfo = StateInfo("group", Group.EMPTY, eventCodec, eventHandler)

  lazy val commandInfo = (deps: Dependencies) =>
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
  private lazy val commandHandler = h2.commandHandler(createGroup, addUser)

  private val createGroup =
    h2.commandHandlerFor[CreateGroup] { d => (c, s, ctx) =>
      s match
        case Group.EMPTY =>
          ctx.withState(UserAggregate.stateInfo, c.ownerId) >>= { (s2, ctx2) =>
            if s2 == UserAggregate.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
            else ctx.save(ProductTypes.convert[CreateGroup, GroupCreated](c))
          }
        case _ =>
          ctx.fail(IllegalStateException("Already exists"))
    }

  private val addUser =
    h2.commandHandlerFor[AddUser] { d => (c, s, ctx) =>
      s match
        case Group.EMPTY =>
          ctx.fail(IllegalStateException("Group not found"))

        case s: Group =>
          if s.users.contains(c.userId) then ctx.fail(IllegalStateException("Already a member"))
          else
            ctx.withState(UserAggregate.stateInfo, c.userId) >>= { (s2, ctx2) =>
              if s2 == UserAggregate.User.EMPTY then ctx.fail(IllegalStateException("User not found"))
              else ctx.save(ProductTypes.convert[AddUser, UserAdded](c))
            }
    }

  // event handler
  private val eventHandler = h.eventHandler { (s, e) =>
    e.toGroupEvent match
      case e: GroupCreated =>
        s match
          case Group.EMPTY => Group(e.ownerId, e.name, Set(e.ownerId))
          case _           => throw EsException.UnexpectedException

      case e: UserAdded =>
        s.copy(users = s.users + e.userId)

      case _ => throw EsException.UnexpectedException
  }

  // dependencies
  trait Dependencies {}
}
