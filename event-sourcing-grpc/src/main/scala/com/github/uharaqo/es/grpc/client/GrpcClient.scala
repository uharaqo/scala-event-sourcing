package com.github.uharaqo.es.grpc.client

import cats.effect.IO
import com.github.uharaqo.es.proto.eventsourcing.*
import io.grpc.{ManagedChannel, Metadata}

class CommandHandlerClient(channel: ManagedChannel) {

  private val stub = CommandHandlerFs2Grpc.stubResource[IO](channel)

  def call(request: SendCommandRequest): IO[CommandReply] =
    stub.use(_.send(request, Metadata()))
}