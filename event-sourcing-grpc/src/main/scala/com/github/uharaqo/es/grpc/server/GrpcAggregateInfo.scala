package com.github.uharaqo.es.grpc.server

import com.github.uharaqo.es.*
import com.github.uharaqo.es.grpc.codec.{PbDeserializer, PbSerializer}
import scalapb.descriptors.Descriptor
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

class GrpcAggregateInfo[S, C <: GeneratedMessage, E <: GeneratedMessage, D](
  name: AggName,
  emptyState: S,
  commandMessageScalaDescriptor: Descriptor,
  eventHandler: EventHandler[S, E],
  commandHandler: D => CommandHandler[S, C, E],
)(using eCmp: GeneratedMessageCompanion[E], cCmp: GeneratedMessageCompanion[C]) {

  val eventSerializer   = PbSerializer[E]
  val eventDeserializer = PbDeserializer[E]
  val stateInfo         = StateInfo(name, emptyState, eventSerializer, eventDeserializer, eventHandler)

  val commandDeserializer = Map(commandMessageScalaDescriptor.fullName -> PbDeserializer[C])
  val commandRegistry     = (dep: D) => CommandRegistry(stateInfo, commandDeserializer, commandHandler(dep))
}

import cats.effect.IO

extension [S, C, E, ES](ctx: CommandHandlerContext[S, E]) {
  def savePb(events: ES*)(using mapper: ES => E): IO[Seq[EventRecord]] =
    ctx.save(events.map(mapper): _*)
}
