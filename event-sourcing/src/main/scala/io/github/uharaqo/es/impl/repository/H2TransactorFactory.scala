package io.github.uharaqo.es.impl.repository

import cats.effect.{IO, Resource}
import doobie.h2.H2Transactor
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor

object H2TransactorFactory {

  def create(): Resource[IO, Transactor[IO]] =
    for
      ce <- ExecutionContexts.fixedThreadPool[IO](32)
      xa <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "",
        ce
      )
    yield xa
}
