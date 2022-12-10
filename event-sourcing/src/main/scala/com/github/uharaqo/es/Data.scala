package com.github.uharaqo.es

type Bytes   = Array[Byte]
type Fqcn    = String
type Version = Long
type TsMs    = Long

type AggName = String
type AggId   = String

case class AggInfo(name: AggName, id: AggId)

case class VersionedEvent(version: Version, event: Bytes)

case class VersionedState[S](version: Version, state: S)
