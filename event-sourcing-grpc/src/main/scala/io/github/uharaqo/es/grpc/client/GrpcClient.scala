package io.github.uharaqo.es.grpc.client

import cats.effect.IO
import io.github.uharaqo.es.proto.eventsourcing.*
import io.grpc.{ManagedChannel, Metadata}

class CommandHandlerClient(channel: ManagedChannel) {

  private val stub = GrpcCommandHandlerFs2Grpc.stubResource[IO](channel)

  def call(request: SendCommandRequest): IO[CommandReply] =
    stub.use(_.send(request, Metadata()))
}
