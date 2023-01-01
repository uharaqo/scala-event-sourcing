package io.github.uharaqo.es.example.grpc

import cats.effect.{ExitCode, IO, IOApp}
import io.github.uharaqo.es.grpc.client.CommandHandlerClient
import io.github.uharaqo.es.grpc.proto.*
import io.github.uharaqo.es.example.proto.*
import com.google.protobuf.any.Any
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.Metadata
import java.nio.charset.StandardCharsets

object SampleGrpcClient extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val payload = RegisterUser("user1").asMessage
    val request = SendCommandRequest("user", "123", Some(Any.pack(payload)))
    val cli =
      CommandHandlerClient(
        NettyChannelBuilder.forAddress("127.0.0.1", 50051).usePlaintext().build()
      )
    val metadata = Metadata()
    metadata.put(MetadataKeys.requestId, "REQUEST1".getBytes(StandardCharsets.UTF_8))
    for
      v <- cli.call(request, metadata)
      _ <- IO.println(v)
    yield ExitCode.Success
  }
}
