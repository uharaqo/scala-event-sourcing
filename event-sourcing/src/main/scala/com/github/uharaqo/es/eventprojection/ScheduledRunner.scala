package com.github.uharaqo.es.eventprojection

import cats.*
import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.implicits.*

import scala.concurrent.duration.*

type Ticker = () => IO[Option[Unit]]

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

  def apply[S](
    ticker: Resource[IO, Ticker],
    task: S => IO[S],
    pollingInterval: FiniteDuration,
    initialState: S,
  ): Resource[IO, Unit] =
    for
      t <- ticker
      _ <- awaitAndRun(t, task, pollingInterval, initialState).background
    yield ()

  private def awaitAndRun[S](t: Ticker, task: S => IO[S], pollingInterval: FiniteDuration, prevState: S): IO[_] =
    t()
      >>= {
        case Some(_) => task(prevState)
        case None    => IO.sleep(pollingInterval) >> prevState.pure
      }
      >>= { last => awaitAndRun(t, task, pollingInterval, last) }
}

/*
object TimerBackground extends IOApp {
  type TriggerEvent = Int

  def newQueue(): IO[Queue[IO, TriggerEvent]] = Queue.bounded[IO, TriggerEvent](64)

  def scheduler(q: Queue[IO, TriggerEvent], i: Int): IO[Unit] =
    IO(println(s"Publishing $i"))
      >> q.offer(i)
      >> IO.sleep(1.second).flatMap(_ => scheduler(q, i + 1))

  def consumer(q: Queue[IO, TriggerEvent]): IO[Unit] =
    for
      maybe <- q.tryTake
      _ <- maybe match {
        case Some(value) => IO.println(value)
        case None        => IO.sleep(500 millis)
      }
      _ <- consumer(q)
    yield ()

  override def run(args: List[String]): IO[ExitCode] =

    val queue      = Resource.eval(newQueue())
    val terminator = Resource.eval(IO.println("--- START ---") >> IO.sleep(5.seconds) >> IO.println("--- END ---"))

    val p =
      queue flatMap { q =>
        consumer(q).background
          >> scheduler(q, 1).background
          >> terminator
      }
    p.use(_ => IO(ExitCode.Success))
}

object ProjectionDispatcher {
  sealed trait ProjectionTrigger

  object ProjectionTrigger:
    case object Timer                   extends ProjectionTrigger
    case class NewEvent(id: ResourceId) extends ProjectionTrigger

  case class SerializedEventRecord(id: ResourceIdentifier, version: Version, event: Serialized)

  trait ProjectionRepository {
    def load(resourceName: ResourceName, verGt: Version): Stream[IO, SerializedEventRecord]
  }

  // https://stackoverflow.com/a/65635013
  trait Sender:
    def send(t: ProjectionTrigger): Unit

  object Sender:
    def apply(bufferSize: Int): IO[(Sender, Stream[IO, ProjectionTrigger])] =
      for q <- Queue.bounded[IO, ProjectionTrigger](bufferSize)
      yield
        import unsafe.implicits.*
        val sender: Sender                        = (t: ProjectionTrigger) => q.offer(t).unsafeRunSync()
        def stream: Stream[IO, ProjectionTrigger] = Stream.eval(q.take) ++ stream
        (sender, stream)
}

import com.github.uharaqo.es.eventprojection.EventProjections.ProjectionRepository

class ProjectionProcessor[E](
  private val eventDeserializer: EventDeserializer[E],
  private val projectionRepository: ProjectionRepository,
  private val resourceName: ResourceName,
  private val projection: (ResourceIdentifier, Version, E) => IO[Unit],
) {
  import com.github.uharaqo.es.eventprojection.EventProjections.*
  import unsafe.implicits.*

  def start: Sender = {
    val (sender: Sender, projectionTriggers: Stream[IO, ProjectionTrigger]) =
      Sender(64)
        // we have to run it preliminary to make `sender` available to external system
        .unsafeRunSync()

    // TODO: send events to the sender

    val projectionTriggerSubscriber: Stream[IO, FiberIO[List[Nothing]]] =
      projectionTriggers.evalMap { t =>
        val source =
          t match
            case ProjectionTrigger.Timer =>
              projectionRepository.load(resourceName, 0L)
            case ProjectionTrigger.NewEvent(id) =>
              projectionRepository.load(resourceName, 0L)

        val pipe = source.foreach { r =>
          for
            e <- eventDeserializer(r.event)
            _ <- projection(r.id, r.version, e)
          yield ()
        }

        pipe.compile.toList.start
      }

    // start in a separate fiber
    projectionTriggerSubscriber.compile.toList.start.unsafeRunSync()

    sender
  }
}
 */
