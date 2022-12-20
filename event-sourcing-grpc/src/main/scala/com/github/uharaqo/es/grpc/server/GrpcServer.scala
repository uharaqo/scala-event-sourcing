package com.github.uharaqo.es.grpc.server

import cats.effect.{ExitCode, IO}
import com.github.uharaqo.es.proto.eventsourcing.*
import fs2.grpc.syntax.all.*
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder

class GrpcServer(
  service: GrpcCommandHandlerFs2Grpc[IO, Metadata],
  serverBuilder: NettyServerBuilder = NettyServerBuilder.forPort(50051),
) {

  def start: IO[ExitCode] =
    GrpcCommandHandlerFs2Grpc
      .bindServiceResource[IO](service)
      .use { s =>
        serverBuilder.addService(s).resource[IO].evalMap(server => IO(server.start())).useForever
      }
      .as(ExitCode.Success)
}
