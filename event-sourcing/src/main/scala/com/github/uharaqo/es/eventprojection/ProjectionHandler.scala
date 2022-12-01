package com.github.uharaqo.es.eventprojection

import cats.effect.*
import cats.implicits.*
import com.github.uharaqo.es.eventsourcing.EventSourcing.*

import scala.concurrent.duration.*

object ProjectionHandler {
  import EventProjections.*
  import org.typelevel.log4cats.Logger
  import org.typelevel.log4cats.slf4j.Slf4jLogger

  implicit def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def apply[E, S](
    deserializer: EventDeserializer[E],
    projection: Projection[E, S],
    maxRetry: Int,
    retryInterval: FiniteDuration,
  ): (EventRecord, S) => IO[S] = { (event, prev) =>

    val deserialize = { (event: EventRecord) =>
      deserializer(event.event)
        .map(e => ProjectionEvent(event.id, event.version, event.timestamp, e))
        .recoverWith(t => IO.raiseError(UnrecoverableException("Event deserialization failure", t)))
    }

    lazy val handler: (FiniteDuration, Int, ProjectionEvent[E]) => IO[S] = { (retryInterval, remainingRetry, event) =>
      projection(event) >>= {
        case Right(s) =>
          s.pure

        case Left(err) =>
          err match
            case _: TemporaryException =>
              if (remainingRetry > 0)
                Logger[IO].warn(err)(s"Projection temporary failure. Retrying (remaining: $remainingRetry")
                  >> IO.sleep(retryInterval)
                  >> handler(retryInterval * 2, remainingRetry - 1, event)
              else
                IO.raiseError(UnrecoverableException("Projection retry failure", err))
            case _: UnrecoverableException =>
              IO.raiseError(err)
            case _: Throwable =>
              IO.raiseError(UnrecoverableException("Unhandled projection error", err))
      }
    }

    deserialize(event)
      >>= { e => handler(retryInterval, maxRetry, e) }
  }
}
