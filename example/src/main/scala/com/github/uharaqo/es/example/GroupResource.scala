package com.github.uharaqo.es.example

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.*
import com.github.uharaqo.es.proto.example.*
import com.github.uharaqo.es.grpc.codec.*
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

object GroupResource {
  import GroupResource.*

  // commands
//  sealed trait GroupCommand
//  case class CreateGroup(ownerId: String, name: String) extends GroupCommand
//  case class AddUser(userId: String)                    extends GroupCommand
//
//  // events
//  sealed trait GroupEvent
//  case class GroupCreated(ownerId: String, name: String) extends GroupEvent
//  case class UserAdded(userId: String)                   extends GroupEvent

  // state
  case class Group(ownerId: String, name: String, users: Set[String])
  object Group:
    val EMPTY = Group("", "", Set.empty)

  import com.github.plokhotnyuk.jsoniter_scala.macros._
  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonCodec => _, _}

  /** for testing */
  val commandSerializer: Serializer[GroupCommand] = c => IO(c.asMessage.toByteArray)

  val deserializers: Map[Fqcn, Deserializer[GroupCommand]] = {
    def deserializer[A <: GeneratedMessage](clazz: Class[A])(implicit cmp: GeneratedMessageCompanion[A]) =
      clazz.getCanonicalName().nn -> PbDeserializer[A]
    Seq(
      deserializer(classOf[CreateGroup]),
      deserializer(classOf[AddUser]),
    ).toMap

    //   def deserializer[C](c: Class[C])(implicit codec: JsonValueCodec[C]): (Fqcn, Deserializer[C]) =
    //     (c.getCanonicalName().nn, JsonCodec[C]().deserializer)
    //   Map(
    //     deserializer(classOf[CreateGroup])(JsonCodecMaker.make),
    //     deserializer(classOf[AddUser])(JsonCodecMaker.make),
    //   )
  }

  private val eventHandler: EventHandler[Group, GroupEvent] = { (s, e) =>
    e.asNonEmpty.get match
      case GroupCreated(ownerId, name, unknownFields) =>
        s match
          case Group.EMPTY => Group(ownerId, name, Set(ownerId))
          case _           => throw EsException.UnexpectedException

      case UserAdded(userId, unknownFields) =>
        s.copy(users = s.users + userId)
  }

  private val commandHandler: CommandHandler[Group, GroupCommand, GroupEvent] = { (s, c, ctx) =>
    import ctx.*
    s match
      case Group.EMPTY =>
        c.asNonEmpty.get match
          case CreateGroup(ownerId, name, unknownFields) =>
            ctx.withState(UserResource.info, ownerId) { (s, ctx2) =>
              if (s == UserResource.User.EMPTY)
                ctx.fail(IllegalStateException("User not found"))
              else
                ctx.save(GroupCreated(ownerId, name))
            }

          case _ =>
            fail(IllegalStateException("Group not found"))

      case Group(name, point, users) =>
        c.asNonEmpty.get match
          case CreateGroup(ownerId, name, unknownFields) =>
            fail(IllegalStateException("Already exists"))

          case AddUser(userId, unknownFields) =>
            if (users.contains(userId))
              fail(IllegalStateException("Already a member"))
            else
              ctx.withState(UserResource.info, userId) { (s, ctx2) =>
                if (s == UserResource.User.EMPTY)
                  ctx.fail(IllegalStateException("User not found"))
                else
                  ctx.save(UserAdded(userId))
              }
  }

  val info: StateInfo[Group, GroupEvent] = {
//    import com.github.plokhotnyuk.jsoniter_scala.macros._
//    import com.github.plokhotnyuk.jsoniter_scala.core._
//    implicit val codec = JsonCodecMaker.make[GroupEvent]
//    val eCodec         = JsonCodec()
    val serializer: Serializer[GroupEvent]     = o => IO(o.asMessage.toByteArray)
    val deserializer: Deserializer[GroupEvent] = bs => IO(GroupEventMessage.parseFrom(bs).toGroupEvent)

    StateInfo("group", Group.EMPTY, serializer, deserializer, eventHandler)
  }

  def newCommandRegistry(): CommandRegistry =
    CommandRegistry(info, deserializers, commandHandler)
}
