package loggingexperiment.logbackzio

import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import net.logstash.logback.argument.StructuredArguments
import net.logstash.logback.marker.{LogstashMarker, Markers}
import org.slf4j.{Logger, LoggerFactory}
import zio._

final case class A(x: Int, y: String)
final case class B(a: A, b: Boolean)

trait Log {
  def log: Log.Service
}

object Log {

  trait Service {
    def info[A](format: String, xName: String, x: A)(
      implicit e: Encoder[A]
    ): UIO[Unit]
    def info[A, B](format: String, xName: String, x: A, yName: String, y: B)(
      implicit ex: Encoder[A],
      ey: Encoder[B]
    ): UIO[Unit]
    def addContext[A](format: String, xName: String, x: A)(
      implicit e: Encoder[A]
    ): UManaged[Unit]
  }

  def log: ZIO[Log, Nothing, Log.Service] =
    ZIO.access[Log](_.log)

  private class LogImpl(logger: Logger,
                        mdc: FiberRef[Map[String, List[(Any, Encoder[Any])]]])
      extends Service {

    private def log(body: LogstashMarker => Unit): UIO[Unit] =
      for {
        mdc <- mdc.get
        mdcNormalized = mdc.toList.map {
          case (k, v) => (k, v.head match { case (v, e) => e(v).toString })
        }
        markers = mdcNormalized.map { case (k, v) => Markers.appendRaw(k, v) }
        _ <- UIO.effectTotal {
          body(Markers.aggregate(markers: _*))
        }
      } yield ()

    override def addContext[A](format: String, xName: String, x: A)(
      implicit e: Encoder[A]
    ): UManaged[Unit] = {
      Managed.make {
        for {
          _ <- mdc.update { mdc =>
            mdc.get(xName) match {
              case Some(list) =>
                mdc + ((xName, (x, e.asInstanceOf[Encoder[Any]]) :: list))
              case None =>
                mdc + ((xName, (x, e.asInstanceOf[Encoder[Any]]) :: Nil))
            }
          }
        } yield ()
      } { _: Unit =>
        for {
          _ <- mdc.update { mdc =>
            mdc.get(xName) match {
              case Some(_ :: Nil) =>
                mdc - xName
              case Some(_ :: tail) =>
                mdc + ((xName, tail))
            }
          }
        } yield ()
      }
    }

    override def info[A](format: String, xName: String, x: A)(
      implicit e: Encoder[A]
    ): UIO[Unit] =
      log { mdc =>
        logger.info(
          mdc,
          format,
          StructuredArguments.raw(xName, x.asJson.toString),
        )
      }

    override def info[A, B](
      format: String,
      xName: String,
      x: A,
      yName: String,
      y: B
    )(implicit ex: Encoder[A], ey: Encoder[B]): UIO[Unit] =
      log { mdc =>
        logger.info(
          mdc,
          format,
          StructuredArguments.raw(xName, x.asJson.toString),
          StructuredArguments.raw(yName, y.asJson.toString): Any,
        )
      }
  }

  def make(logger: Logger): UIO[Log] = {
    for {
      mdc <- FiberRef.make(Map.empty[String, List[(Any, Encoder[Any])]])
      logImpl = new LogImpl(logger, mdc)
      svc = new Log {
        override def log: Service = logImpl
      }
    } yield svc

  }
}

object Main extends App {

  val logger: Logger = LoggerFactory.getLogger(getClass)
  val o = B(A(123, "Hello"), b = true)

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    init.fold(_ => 1, _ => 0)

  def init: Task[Unit] =
    for {
      log <- Log.make(logger)
      result <- program.provide(log)
    } yield result

  def program: RIO[Log, Unit] = {
    for {
      logger <- Log.log
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
