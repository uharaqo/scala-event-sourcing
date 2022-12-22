package io.github.uharaqo.es.grpc.server

import io.github.uharaqo.es.*
import io.github.uharaqo.es.grpc.codec.PbCodec
import scalapb.descriptors.Descriptor
import scalapb.GeneratedMessageCompanion

import cats.effect.IO

extension [S, E](ctx: CommandHandlerContext[S, E]) {
  def save[ES](events: ES*)(using mapper: ES => E): IO[EventRecords] =
    ctx.save(events.map(mapper)*)
}
