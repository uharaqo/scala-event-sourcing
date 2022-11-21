package com.github.uharaqo.es

import cats.effect.*
import cats.implicits.*
import cats.syntax.all.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*
import com.github.uharaqo.es.io.sql.*
import com.github.uharaqo.es.UserResource
import com.github.uharaqo.es.UserResource.*
import munit.*

class EventSourcingSuite extends CatsEffectSuite {

  private val registerUser = classOf[RegisterUser].getCanonicalName()
  private val addPoint     = classOf[AddPoint].getCanonicalName()
  // private val sendPoint    = classOf[SendPoint].getCanonicalName()

  private var repo: DoobieEventRepository   = null
  private var dispatcher: CommandDispatcher = null

  override def beforeAll(): Unit = {
    val transactor = H2TransactorFactory.create()
    repo = DoobieEventRepository(transactor)

    dispatcher = getCommandRegistry(UserResource.newUserCommandProcessor(repo)) match {
      case Right(r) => CommandDispatcher(r)
      case Left(e)  => throw e
    }
  }

  test("user") {

    val id1 = ResourceId("user", "i1")
    val id2 = ResourceId("user", "i2")
    val requests = List(
      CommandRequest(id1, registerUser, """{"name": "Alice"}"""),
      CommandRequest(id1, addPoint, """{"point": 30}"""),
      CommandRequest(id1, addPoint, """{"point": 80}"""),
      CommandRequest(id2, registerUser, """{"name": "Bob"}"""),
      // TODO: error test
      // CommandRequest(id2, registerUser, """{"name": "Bob"}"""),

      // TODO: how to handle this?
      // CommandRequest(id1, sendPoint, """{"recipientId": "i2", "point": 10}"""),
      // CommandRequest(id1, sendPoint, """{"recipientId": "i2", "point": 9999}"""),
    )

    val result = for {
      // init DB
      _ <- repo.initTables()

      // run commands
      r <- requests.traverse(dispatcher)
      _ <- IO(r.foreach(println))

      // read records in DB
      rs1 <- repo.reader(id1).compile.toVector
      _   <- IO.println(rs1)
      rs2 <- repo.reader(id2).compile.toVector
      _   <- IO.println(rs2)
    } yield (rs1, rs2)

    result.map {
      case (rs1, rs2) =>
        assertEquals(rs1.map(_.version), Seq.range(1L, rs1.size + 1L).toVector)
        assertEquals(
          rs1.map(_.event),
          Vector(
            """{"UserRegistered":{"name":"Alice"}}""",
            """{"PointAdded":{"point":30}}""",
            """{"PointAdded":{"point":80}}""",
          )
        )

        assertEquals(rs2.map(_.version), Seq.range(1L, rs2.size + 1L).toVector)
        assertEquals(
          rs2.map(_.event),
          Vector(
            """{"UserRegistered":{"name":"Bob"}}""",
          )
        )
    }
  }
}
