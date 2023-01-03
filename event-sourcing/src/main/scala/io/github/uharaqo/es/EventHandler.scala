package io.github.uharaqo.es

import scala.util.Try

/** Generate the next state based on a previous state and next event */
type EventHandler[S, E] = (S, E) => Try[S]
