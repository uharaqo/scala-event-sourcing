package io.github.uharaqo.es.grpc.server

import io.github.uharaqo.es.*
import io.github.uharaqo.es.grpc.codec.PbCodec
import scalapb.descriptors.Descriptor
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

object GrpcAggregateInfo {
  def apply[S, C, E, D](
    name: AggName,
    emptyState: S,
    eventCodec: Codec[E],
    eventHandler: EventHandler[S, E],
    commandFqcn: Fqcn,
    commandDeserializer: Deserializer[C],
    commandHandlerFactory: D => CommandHandler[S, C, E],
  ): AggregateInfo[S, C, E, D] =
    val stateInfo = StateInfo(name, emptyState, eventCodec, eventHandler)
    val commandInfo =
      (deps: D) => CommandInfo(stateInfo, commandFqcn, commandDeserializer, commandHandlerFactory(deps))

    AggregateInfo(stateInfo, commandInfo)

  def apply[S, C <: GeneratedMessage, E <: GeneratedMessage, D](
    name: AggName,
    emptyState: S,
    commandMessageScalaDescriptor: Descriptor,
    eventHandler: EventHandler[S, E],
    commandHandlerFactory: D => CommandHandler[S, C, E],
  )(using eCmp: GeneratedMessageCompanion[E], cCmp: GeneratedMessageCompanion[C]): AggregateInfo[S, C, E, D] =
    AggregateInfo(
      name,
      emptyState,
      PbCodec[E],
      eventHandler,
      commandMessageScalaDescriptor.fullName,
      PbCodec[C],
      deps => commandHandlerFactory(deps)
    )
}

import cats.effect.IO

extension [S, E](ctx: CommandHandlerContext[S, E]) {
  def save[ES](events: ES*)(using mapper: ES => E): IO[EventRecords] =
    ctx.save(events.map(mapper)*)
}
