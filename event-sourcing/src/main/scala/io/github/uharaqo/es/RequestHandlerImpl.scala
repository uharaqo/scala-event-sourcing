package io.github.uharaqo.es

import cats.effect.IO

object CommandRegistry {
  def apply[S, C, E](
    stateInfo: StateInfo[S, E],
    deserializers: Map[Fqcn, Deserializer[C]],
    handler: CommandHandler[S, C, E],
  ): CommandRegistry =
    deserializers.map { case (k, v) => (k, CommandInfo(stateInfo, k, v, handler)) }
}
