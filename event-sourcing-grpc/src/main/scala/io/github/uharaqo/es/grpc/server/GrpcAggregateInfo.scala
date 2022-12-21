package io.github.uharaqo.es.grpc.server

import io.github.uharaqo.es.*
import io.github.uharaqo.es.grpc.codec.PbCodec
import scalapb.descriptors.Descriptor
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

class GrpcAggregateInfo[S, C <: GeneratedMessage, E <: GeneratedMessage, D](
  val name: AggName,
  emptyState: S,
  commandMessageScalaDescriptor: Descriptor,
  eventHandler: EventHandler[S, E],
  commandHandlerFactory: D => CommandHandler[S, C, E],
)(using eCmp: GeneratedMessageCompanion[E], cCmp: GeneratedMessageCompanion[C]) {

  val eventCodec   = PbCodec[E]
  val commandCodec = PbCodec[C]
  val stateInfo    = StateInfo(name, emptyState, eventCodec, eventHandler)

  val commandDeserializer = Map(commandMessageScalaDescriptor.fullName -> commandCodec.deserializer)
  val commandRegistry     = (dep: D) => CommandRegistry(stateInfo, commandDeserializer, commandHandlerFactory(dep))
}

import cats.effect.IO

extension [S, E](ctx: CommandHandlerContext[S, E]) {
  def save[ES](events: ES*)(using mapper: ES => E): IO[EventRecords] =
    ctx.save(events.map(mapper)*)
}
