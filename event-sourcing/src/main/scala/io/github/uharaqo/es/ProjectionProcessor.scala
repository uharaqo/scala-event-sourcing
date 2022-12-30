package io.github.uharaqo.es

import cats.effect.std.Queue
import cats.effect.{IO, Resource, Sync}
import cats.implicits.*

import scala.concurrent.duration.FiniteDuration
import cats.data.OptionT

trait ProjectionProcessor:
  def apply(event: EventRecord): IO[SeqId]

object ProjectionProcessor {
  import io.github.uharaqo.es.ProjectionException.*
  import org.typelevel.log4cats.Logger
  import org.typelevel.log4cats.slf4j.Slf4jLogger

  implicit def logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  def apply[E](
    stateInfo: StateInfo[?, E],
    projection: Projection[E],
    maxRetry: Int,
    retryInterval: FiniteDuration,
  ): ProjectionProcessor = { event =>

    val deserialize = (event: EventRecord) =>
      stateInfo.eventCodec
        .convert(event.event)
        .map(e => ProjectionEvent(event.id, event.version, event.seqId, e))
        .handleErrorWith(t => IO.raiseError(UnrecoverableException("Event deserialization failure", t.some)))

    lazy val handle: (ProjectionEvent[E], FiniteDuration, Int) => IO[SeqId] = {
      (event, retryInterval, remainingRetry) =>
        projection(event) >>= {
          case Right(_) =>
            IO.pure(event.seqId)

          case Left(err) =>
            err match
              case _: TemporaryException =>
                if remainingRetry > 0 then
                  logger.warn(err)(s"Projection temporary failure. Retrying (remaining: $remainingRetry)")
                    >> IO.sleep(retryInterval)
                    >> handle(event, retryInterval * 2, remainingRetry - 1)
                else IO.raiseError(UnrecoverableException("Projection retry failure", err.some))
              case _: UnrecoverableException =>
                IO.raiseError(err)
              case _: Throwable =>
                IO.raiseError(UnrecoverableException("Unhandled projection error", err.some))
        }
    }

    deserialize(event) >>= { handle(_, retryInterval, maxRetry) }
  }
}

object ScheduledProjection {
  type Ticker = () => IO[Option[Unit]]

  /** Create a scheduled projection
    *
    * @param projectionId
    *   unique ID for this projection
    * @param name
    *   aggregate name to be queried
    * @param eventRepository
    *   event repository
    * @param projectionRepository
    *   projection repository
    * @param processor
    *   processor
    * @param loadInterval
    *   duration between scheduled polling
    * @param pollingInterval
    * @return
    *   scheduled projection resource
    */
  def apply(
    projectionId: String,
    name: AggName,
    eventRepository: EventRepository,
    projectionRepository: ProjectionRepository,
    processor: ProjectionProcessor,
    loadInterval: FiniteDuration,
    pollingInterval: FiniteDuration,
  ): Resource[IO, Unit] = {
    val ticker = Ticker(loadInterval)

    val task =
      projectionRepository.runWithLock(projectionId) { prevTs =>
        eventRepository
          .queryByName(EventQuery(name, prevTs))
          .evalMap(processor(_))
          .compile
          .last
      }

    ScheduledRunner(task, ticker, pollingInterval)
  }

  object Ticker {

    def apply(publishInterval: FiniteDuration): Resource[IO, Ticker] =
      for
        q <- Resource.eval(Queue.bounded[IO, Unit](1))
        _ <- schedule(q, publishInterval).background
      yield () => q.tryTake

    private def schedule(q: Queue[IO, Unit], publishInterval: FiniteDuration): IO[Unit] =
      q.tryOffer(())
        >> IO.sleep(publishInterval)
        >> schedule(q, publishInterval)
  }

  object ScheduledRunner {
    import org.typelevel.log4cats.Logger
    import org.typelevel.log4cats.slf4j.Slf4jLogger

    implicit def logger: Logger[IO] = Slf4jLogger.getLogger[IO]

    def apply(
      task: IO[Boolean],
      ticker: Resource[IO, Ticker],
      pollingInterval: FiniteDuration,
    ): Resource[IO, Unit] =
      for
        t <- ticker
        _ <- awaitAndRun(t, task, pollingInterval).background
      yield ()

    private def awaitAndRun(ticker: Ticker, task: IO[Boolean], pollingInterval: FiniteDuration): IO[Unit] =
      ticker()
        >>= {
          case Some(_) =>
            // TODO: retry on false? review the entire flow. add limit?
            // TODO: why do we need this sleep? busy loop?
            task.handleErrorWith(t => logger.warn(t)(s"Projection failure") >> IO.unit)
          case None => IO.sleep(pollingInterval) >> IO.unit
        }
        >>= { _ => awaitAndRun(ticker, task, pollingInterval) }
  }
}
