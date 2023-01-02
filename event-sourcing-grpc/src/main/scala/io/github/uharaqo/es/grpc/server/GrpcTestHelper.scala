package io.github.uharaqo.es.grpc.server

import cats.effect.IO
import io.github.uharaqo.es.*

extension [S, E](ctx: CommandHandlerContext[S, E]) {
  def save[ES](events: ES*)(using mapper: ES => E): IO[EventRecords] =
    import io.github.uharaqo.es.save as originalSave // to avoid name conflict
    ctx.originalSave(events.map(mapper)*)
}
