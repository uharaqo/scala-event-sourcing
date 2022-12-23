package io.github.uharaqo.es.repository

import cats.effect.{IO, Resource}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor

object PostgresTransactorFactory {

  /** CREATE DATABASE devdb;
    *
    * \c devdb
    *
    * CREATE ROLE testuser WITH LOGIN PASSWORD 'testpass';
    *
    * GRANT ALL ON ALL TABLES IN SCHEMA public TO testuser;
    */
  def create(): Resource[IO, Transactor[IO]] =
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](32)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        "jdbc:postgresql:devdb",
        "testuser",
        "testpass",
        ce, // await connection here
      )
    } yield xa
}
