package com.github.uharaqo.es.example.grpc
import cats.effect.{ExitCode, IO, IOApp}
import com.github.uharaqo.es.grpc.client.CommandHandlerClient
import com.github.uharaqo.es.proto.eventsourcing.SendCommandRequest
import com.github.uharaqo.es.proto.example.RegisterUser
import com.google.protobuf.any.Any
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder

object SampleGrpcClient extends IOApp {
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
