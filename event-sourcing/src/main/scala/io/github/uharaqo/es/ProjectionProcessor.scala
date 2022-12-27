package io.github.uharaqo.es

import cats.effect.std.Queue
import cats.effect.{IO, Resource, Sync}
import cats.implicits.*

import scala.concurrent.duration.FiniteDuration

trait ProjectionProcessor[T]:
  def apply(event: EventRecord, previous: T): IO[T]

object ProjectionProcessor {
  import io.github.uharaqo.es.ProjectionException.*
  import org.typelevel.log4cats.Logger
  import org.typelevel.log4cats.slf4j.Slf4jLogger

  implicit def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def apply[E, T](
    deserializer: Deserializer[E],
    projection: Projection[E, T],
    maxRetry: Int,
    retryInterval: FiniteDuration,
  ): ProjectionProcessor[T] = { (event, prev) =>

    val deserialize = (event: EventRecord) =>
      deserializer
        .convert(event.event)
        .map(e => ProjectionEvent(event.id, event.version, event.timestamp, e))
        .handleErrorWith(t => IO.raiseError(UnrecoverableException("Event deserialization failure", t.some)))

    lazy val handle: (ProjectionEvent[E], FiniteDuration, Int) => IO[T] = { (event, retryInterval, remainingRetry) =>
      projection(event) >>= {
        case Right(s) =>
          s.pure

        case Left(err) =>
          err match
            case _: TemporaryException =>
              if remainingRetry > 0 then
                Logger[IO].warn(err)(s"Projection temporary failure. Retrying (remaining: $remainingRetry)")
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

  def apply[T, E](
    processor: ProjectionProcessor[T],
    initialState: T,
    query: T => EventQuery,
    repo: EventRepository,
    loadInterval: FiniteDuration,
    pollingInterval: FiniteDuration,
  ): Resource[IO, Unit] = {
    val ticker = Ticker(loadInterval)

    ScheduledRunner[T](
      ticker,
      prev =>
        repo
          .load(query(prev))
          .evalMap(record => processor(record, prev))
          .compile
          .last
          >>= { _.getOrElse(prev).pure[IO] },
      pollingInterval,
      initialState,
    )
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

    def apply[T](
      ticker: Resource[IO, Ticker],
      task: T => IO[T],
      pollingInterval: FiniteDuration,
      initialState: T,
    ): Resource[IO, Unit] =
      for
        t <- ticker
        _ <- awaitAndRun(t, task, pollingInterval, initialState).background
      yield ()

    private def awaitAndRun[T](t: Ticker, task: T => IO[T], pollingInterval: FiniteDuration, prevState: T): IO[?] =
      t()
        >>= {
          case Some(_) => task(prevState)
          case None    => IO.sleep(pollingInterval) >> prevState.pure
        }
        >>= { last => awaitAndRun(t, task, pollingInterval, last) }
  }
}
