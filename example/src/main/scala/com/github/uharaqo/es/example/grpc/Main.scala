package com.github.uharaqo.es.example.grpc

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.github.uharaqo.es.*
import com.github.uharaqo.es.example.{GroupResource, UserResource}
import com.github.uharaqo.es.grpc.client.CommandHandlerClient
import com.github.uharaqo.es.grpc.server.GrpcServer
import com.github.uharaqo.es.impl.repository.{DoobieEventRepository, H2TransactorFactory}
import com.github.uharaqo.es.proto.eventsourcing.*
import com.github.uharaqo.es.proto.example.*
import com.github.uharaqo.es.proto.example.UserCommandMessage.SealedValue
import com.google.protobuf.any.Any
import com.google.protobuf.struct.{Struct, Value}
import com.google.protobuf.{GeneratedMessageV3, StringValue}
import doobie.util.transactor.Transactor
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.{Metadata, Status}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

object Client extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val userUser = RegisterUser("user1")
    val request  = SendCommandRequest("user", "123", Some(Any.pack(userUser)))
    val cli =
      CommandHandlerClient(
        NettyChannelBuilder.forAddress("127.0.0.1", 50051).usePlaintext().build()
      )
    for
      v <- cli.call(request)
      _ <- IO.println(v)
    yield ExitCode.Success
  }
}

object Server extends IOApp {
  private val registry =
    (UserResource.newCommandRegistry().view.mapValues(a => a.copy(handler = debug(a.handler)))
      ++ GroupResource.newCommandRegistry().view.mapValues(a => a.copy(handler = debug(a.handler)))).toMap
  private val transactor = H2TransactorFactory.create()
  private val repository = (xa: Transactor[IO]) => DoobieEventRepository(xa)
  private val processor  = (repo: EventRepository) => GrpcCommandProcessor(registry, repo)
  private val server =
    (processor: GrpcCommandProcessor) =>
      GrpcServer(
        new CommandHandlerFs2Grpc[IO, Metadata] {
          override def send(request: SendCommandRequest, ctx: Metadata): IO[CommandReply] = {
            val parsed = parse(request)
            processor.command(parsed).map(r => CommandReply(r.version, r.message))
          }

          override def load(request: LoadStateRequest, ctx: Metadata): IO[StateReply] = ???
        }
      )

  override def run(args: List[String]): IO[ExitCode] =
    (for
      xa <- transactor
      repo = repository(xa)
      _    <- Resource.eval(repo.initTables())
      code <- Resource.eval((repository andThen processor andThen server)(xa).start)
    yield code)
      .use(IO(_))

  private def parse(request: SendCommandRequest): CommandRequest =
    request.payload
      .flatMap { p =>
        val v = p.unpack[RegisterUser]
        println(v)
        try
          Some(
            CommandRequest(
              // TODO: validation
              info = AggInfo(request.aggregate, request.id),
              name = p.typeUrl.split('/').last,
              payload = p.value.toByteArray
            )
          )
        catch case _ => None
      }
      .getOrElse(throw Status.INVALID_ARGUMENT.asRuntimeException())
}
