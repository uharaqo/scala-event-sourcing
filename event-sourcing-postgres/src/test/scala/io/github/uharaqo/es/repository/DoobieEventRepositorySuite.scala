package io.github.uharaqo.es.repository

import cats.effect.{ExitCode, IO, Resource}
import io.github.uharaqo.es.TsMs
import munit.CatsEffectSuite

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

class DoobieEventRepositorySuite extends CatsEffectSuite {
  val provider =
    for //
      xa <- H2TransactorFactory.create()
//      xa <- PostgresTransactorFactory.create()
    yield DoobieEventRepository(xa)

  test("concurrent reads must be prevented") {
    val projectionId = "Projection1"

    val initialTs = AtomicLong(0L)

    val task = (name: String, prevTsMs: TsMs) =>
      if initialTs.get() == prevTsMs then
        IO.println(s"$name " + prevTsMs) >> IO.sleep(300 millis) >> IO.pure(Some(System.currentTimeMillis()))
      else
        // adding this timestamp check to simulate lock acquisition failure when used with H2
        // when run with Postgres, this will be logged as expected: could not obtain lock on row in relation "projections"
        // but with H2, both fibers wait for the lock and both of them succeed
        IO.pure(None)

    (for
      repo1 <- provider
      repo2 <- provider

      _ <- Resource.eval(
        for
          _ <- repo1.initTables()
          // insert a row and keep the timestamp
          _ <- repo1.runWithLock(projectionId)(tsMs =>
            IO.pure {
              val initial = System.currentTimeMillis()
              initialTs.set(initial)
              Some(initial)
            }
          )
          _ <- IO.sleep(50 millis)

          a <- repo1
            .runWithLock(projectionId)(tsMs => task("A", tsMs))
            .start
          _ <- IO.sleep(50 millis)

          b <- repo2
            .runWithLock(projectionId)(tsMs => task("B", tsMs))
            .start

          aa <- a.joinWithNever
          bb <- b.joinWithNever

          _ <- IO.println(s"$aa, $bb")
        yield assertEquals((aa, bb), (true, false))
      )
    yield ())
      .use(_ => IO.pure(ExitCode.Success))
  }
}
