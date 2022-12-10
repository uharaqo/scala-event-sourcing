package com.github.uharaqo.es

/** Generate the next state based on a previous state and next event */
type EventHandler[S, E] = (S, E) => S
