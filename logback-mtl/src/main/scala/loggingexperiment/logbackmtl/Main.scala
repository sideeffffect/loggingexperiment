package loggingexperiment.logbackmtl

import java.security.InvalidParameterException

import cats.effect._
import cats.implicits._
import cats.mtl._
import com.olegpy.meow.monix._
import io.circe.Encoder
import io.circe.generic.auto._
import monix.eval._
import monix.execution.Scheduler
import net.logstash.logback.marker.{LogstashMarker, Markers}
import org.slf4j.{Logger, LoggerFactory}

import scala.language.higherKinds

final case class A(x: Int, y: String)
final case class B(a: A, b: Boolean)

trait KeySub {
  def toString: String
}

trait KeyTC[A] {
  def toString(a: A): String
}

trait Log[F[_]] {
  def info[A](message: String): F[Unit]
  def info[A](message: String, name: String, value: A)(
    implicit e: Encoder[A]
  ): F[Unit]
  def info[A](message: String, name: KeySub, value: A)(
    implicit e: Encoder[A]
  ): F[Unit]
  def info[A, K](message: String, name: K, value: A)(implicit e: Encoder[A],
                                                     k: KeyTC[K]): F[Unit]
  def info[A](message: String, ex: Throwable): F[Unit]
  def info[A](message: String, name: String, value: A, ex: Throwable)(
    implicit e: Encoder[A]
  ): F[Unit]
  def withContext[A, B](name: String, value: A)(inner: F[B])(
    implicit e: Encoder[A]
  ): F[B]
  def withContext[A, B](map: Map[String, A])(inner: F[B])(
    implicit e: Encoder[A]
  ): F[B]
  def withContext[A, B, C](name1: String, value1: A, name2: String, value2: B)(
    inner: F[C]
  )(implicit e1: Encoder[A], e2: Encoder[B]): F[C]
}

object Log {
  private class LogImpl[F[_]](logger: Logger)(
    implicit FApplicativeLocal: ApplicativeLocal[F, Map[String, String]],
    FSync: Sync[F]
  ) extends Log[F] {

    private def log(body: LogstashMarker => Unit): F[Unit] =
      for {
        mdc <- FApplicativeLocal.ask
        markers = mdc.toList.map { case (k, v) => Markers.appendRaw(k, v) }
        _ <- FSync.delay {
          body(Markers.aggregate(markers: _*))
        }
      } yield ()

    override def withContext[A, B](name: String, value: A)(
      inner: F[B]
    )(implicit e: Encoder[A]): F[B] = {
      if (logger.isTraceEnabled || logger.isDebugEnabled || logger.isInfoEnabled || logger.isWarnEnabled || logger.isErrorEnabled) {
        FApplicativeLocal.local(_ + ((name, e(value).spaces2)))(inner)
      } else {
        inner
      }
    }

    override def withContext[A, B](
      map: Map[String, A]
    )(inner: F[B])(implicit e: Encoder[A]): F[B] = {
      if (logger.isTraceEnabled || logger.isDebugEnabled || logger.isInfoEnabled || logger.isWarnEnabled || logger.isErrorEnabled) {
        FApplicativeLocal.local(map.foldLeft(_) {
          case (m, (k, v)) => m + ((k, e(v).spaces2))
        })(inner)
      } else {
        inner
      }
    }

    def withContext[A, B, C](
      name1: String,
      value1: A,
      name2: String,
      value2: B
    )(inner: F[C])(implicit e1: Encoder[A], e2: Encoder[B]): F[C] = {
      if (logger.isTraceEnabled || logger.isDebugEnabled || logger.isInfoEnabled || logger.isWarnEnabled || logger.isErrorEnabled) {
        FApplicativeLocal.local(
          _ + ((name1, e1(value1).spaces2)) + ((name2, e2(value2).spaces2))
        )(inner)
      } else {
        inner
      }
    }

    override def info[A](message: String): F[Unit] =
      if (logger.isInfoEnabled) {
        log { mdc =>
          logger.info(mdc, message)
        }
      } else {
        FSync.unit
      }

    override def info[A](message: String, name: String, value: A)(
      implicit e: Encoder[A]
    ): F[Unit] =
      if (logger.isInfoEnabled) {
        withContext(name, value) {
          log { mdc =>
            logger.info(mdc, message)
          }
        }
      } else {
        FSync.unit
      }

    override def info[A](message: String, name: KeySub, value: A)(
      implicit e: Encoder[A]
    ): F[Unit] =
      if (logger.isInfoEnabled) {
        withContext(name.toString, value) {
          log { mdc =>
            logger.info(mdc, message)
          }
        }
      } else {
        FSync.unit
      }

    override def info[A, K](message: String, name: K, value: A)(
      implicit e: Encoder[A],
      k: KeyTC[K]
    ): F[Unit] =
      if (logger.isInfoEnabled) {
        withContext(k.toString(name), value) {
          log { mdc =>
            logger.info(mdc, message)
          }
        }
      } else {
        FSync.unit
      }

    // only for Throwables for which you don't want to create Encoder, otherwise use `withContext`
    override def info[A](message: String, ex: Throwable): F[Unit] =
      if (logger.isInfoEnabled) {
        log { mdc =>
          logger.info(mdc, message, ex)
        }
      } else {
        FSync.unit
      }

    // only for Throwables for which you don't want to create Encoder, otherwise use `withContext`
    override def info[A](message: String,
                         name: String,
                         value: A,
                         ex: Throwable)(implicit e: Encoder[A]): F[Unit] =
      if (logger.isInfoEnabled) {
        withContext(name, value) {
          log { mdc =>
            logger.info(mdc, message, ex)
          }
        }
      } else {
        FSync.unit
      }
  }

  def make[F[_]](logger: Logger)(
    implicit FApplicativeLocal: ApplicativeLocal[F, Map[String, String]],
    FSync: Sync[F]
  ): Log[F] = {
    new LogImpl(logger)
  }
}

object MonixLog {
  def make(logger: Logger): Task[Log[Task]] = {
    for {
      mdc <- TaskLocal(Map.empty[String, String])
      result = mdc.runLocal { implicit ev =>
        Log.make(logger)
      }
    } yield result
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
      log <- MonixLog.make(logger)
      result <- program(log)
    } yield result

  def program(logger: Log[Task])(implicit sch: Scheduler): Task[Unit] = {
    val ex = new InvalidParameterException("BOOOOOM")
    for {
      _ <- logger.withContext("a", A(1, "x")) {
        logger.withContext("o", o) {
          logger.info("Hello Monix")
        }
      }
      _ <- logger.info("Hello MTL", "o", o, ex)
      _ <- logger.withContext("x", 123, "o", o) {
        logger.info("Hello2 meow", "x", 9)
      }
    } yield ()
  }

}
