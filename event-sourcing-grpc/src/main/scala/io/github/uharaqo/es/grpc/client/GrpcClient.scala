package io.github.uharaqo.es.grpc.client

import cats.effect.IO
import io.github.uharaqo.es.grpc.proto.*
import io.grpc.{ManagedChannel, Metadata}

class CommandHandlerClient(channel: ManagedChannel) {

  private val stub = GrpcCommandHandlerFs2Grpc.stubResource[IO](channel)

  def call(request: SendCommandRequest, metadata: Metadata = Metadata()): IO[SendCommandResponse] =
    stub.use(_.sendCommand(request, metadata))
}
