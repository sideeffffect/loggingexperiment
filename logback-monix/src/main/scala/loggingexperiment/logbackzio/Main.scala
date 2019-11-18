package loggingexperiment.logbackzio

import cats.effect._
import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import monix.eval._
import monix.execution.Scheduler
import net.logstash.logback.argument.StructuredArguments
import net.logstash.logback.marker.{LogstashMarker, Markers}
import org.slf4j.{Logger, LoggerFactory}

final case class A(x: Int, y: String)
final case class B(a: A, b: Boolean)

trait Log {
  def info[A](format: String, xName: String, x: A)(
    implicit e: Encoder[A]
  ): Task[Unit]
  def info[A, B](format: String, xName: String, x: A, yName: String, y: B)(
    implicit ex: Encoder[A],
    ey: Encoder[B]
  ): Task[Unit]
  def addContext[A](format: String, xName: String, x: A)(
    implicit e: Encoder[A],
    sch: Scheduler
  ): Resource[Task, Unit]
}

object Log {
  private class LogImpl(logger: Logger,
                        mdc: TaskLocal[Map[String, List[(Any, Encoder[Any])]]])
      extends Log {

    private def log(body: LogstashMarker => Unit): Task[Unit] =
      for {
        mdc <- mdc.read
        mdcNormalized = mdc.toList.map {
          case (k, v) => (k, v.head match { case (v, e) => e(v).spaces2 })
        }
        markers = mdcNormalized.map { case (k, v) => Markers.appendRaw(k, v) }
        _ <- Task.delay {
          body(Markers.aggregate(markers: _*))
        }
      } yield ()

    override def addContext[A](format: String, xName: String, x: A)(
      implicit e: Encoder[A],
      sch: Scheduler
    ): Resource[Task, Unit] = {
      Resource.make {
        for {
          ctx <- mdc.read
          ctxNew = ctx.get(xName) match {
            case Some(list) =>
              ctx + ((xName, (x, e.asInstanceOf[Encoder[Any]]) :: list))
            case None =>
              ctx + ((xName, (x, e.asInstanceOf[Encoder[Any]]) :: Nil))
          }
          _ <- mdc.write(ctxNew)
        } yield ()
      } { _: Unit =>
        for {
          ctx <- mdc.read
          ctxNew = ctx.get(xName) match {
            case Some(_ :: Nil) =>
              ctx - xName
            case Some(_ :: tail) =>
              ctx + ((xName, tail))
          }
          _ <- mdc.write(ctxNew)
        } yield ()
      }
    }

    override def info[A](format: String, xName: String, x: A)(
      implicit e: Encoder[A]
    ): Task[Unit] =
      log { mdc =>
        logger.info(
          mdc,
          format,
          StructuredArguments.raw(xName, x.asJson.spaces2),
        )
      }

    override def info[A, B](
      format: String,
      xName: String,
      x: A,
      yName: String,
      y: B
    )(implicit ex: Encoder[A], ey: Encoder[B]): Task[Unit] =
      log { mdc =>
        logger.info(
          mdc,
          format,
          StructuredArguments.raw(xName, x.asJson.spaces2),
          StructuredArguments.raw(yName, y.asJson.spaces2): Any,
        )
      }
  }

  def make(logger: Logger): Task[Log] = {
    for {
      mdc <- TaskLocal(Map.empty[String, List[(Any, Encoder[Any])]])
      logImpl = new LogImpl(logger, mdc)
    } yield logImpl
  }

}

object Main extends TaskApp {

  val logger: Logger = LoggerFactory.getLogger(getClass)
  val o = B(A(123, "Hello"), b = true)

  override def run(args: List[String]): Task[ExitCode] =
    init(scheduler)
      .map(_ => ExitCode.Success)
      .executeWithOptions(_.enableLocalContextPropagation)

  def init(implicit sch: Scheduler): Task[Unit] =
    for {
      log <- Log.make(logger)
      result <- program(log)
    } yield result

  def program(logger: Log)(implicit sch: Scheduler): Task[Unit] = {
    for {
      _ <- logger.addContext("XXXXXXX {} ", "yyy", A(567, "YYYYYYYYYYYY")).use {
        _: Unit =>
          for {
            _ <- logger.info("Hello {}", "o", o)
          } yield ()
      }
      _ <- logger.info("Hello {}", "o", o)
      _ <- logger.info("Hello2 {} and {}", "x", 123, "o", o)
    } yield ()
  }

}
