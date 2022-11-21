package com.github.uharaqo.es.io.sql

import cats.effect.*
import cats.implicits.*
import doobie.h2.H2Transactor
import doobie.util.*
import doobie.util.transactor.Transactor

object H2TransactorFactory {

  def create(): Resource[IO, Transactor[IO]] =
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](32)
      xa <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "",
        ce
      )
    } yield xa
}
