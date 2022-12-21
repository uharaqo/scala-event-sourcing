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
  commandHandler: D => CommandHandler[S, C, E],
)(using eCmp: GeneratedMessageCompanion[E], cCmp: GeneratedMessageCompanion[C]) {

  val eventCodec   = PbCodec[E]
  val commandCodec = PbCodec[C]
  val stateInfo    = StateInfo(name, emptyState, eventCodec.serializer, eventCodec.deserializer, eventHandler)

  val commandDeserializer = Map(commandMessageScalaDescriptor.fullName -> commandCodec.deserializer)
  val commandRegistry     = (dep: D) => CommandRegistry(stateInfo, commandDeserializer, commandHandler(dep))
}

import cats.effect.IO

extension [S, C, E, ES](ctx: CommandHandlerContext[S, E]) {
  def savePb(events: ES*)(using mapper: ES => E): IO[Seq[EventRecord]] =
    ctx.save(events.map(mapper)*)
}
