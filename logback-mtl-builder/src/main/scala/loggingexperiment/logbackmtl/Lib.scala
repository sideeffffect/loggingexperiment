package loggingexperiment.logbackmtl

import cats.effect._
import cats.implicits._
import cats.mtl._
import com.olegpy.meow.monix._
import io.circe.Encoder
import io.circe.generic.auto._
import monix.eval._
import net.logstash.logback.marker.Markers
import org.slf4j.{LoggerFactory, Marker}

import scala.language.higherKinds
import scala.reflect.ClassTag

trait Logger[F[_]] {
//  def info: LoggerBuilder2[F]
  def FSync: Sync[F]
  def isInfoEnabled: F[Boolean]
  def underlying: org.slf4j.Logger
//  def info(message: String): F[Unit]
//  def info(message: String, ex: Throwable): F[Unit]
  def apply: Logger.LoggerImpl[F]
  def context[A](name: String, value: A)(implicit e: Encoder[A]): Logger[F]
  def context[A](map: Map[String, A])(implicit e: Encoder[A]): Logger[F]
  def use[A](inner: F[A]): F[A]
}

//trait LoggerBuilder2[F[_]] {
//  def msg(message: String): F[Unit]
//  def msg(ex: Throwable)(message: String): F[Unit]
//}

object Logger {

  class JsonInString private (private[Logger] val raw: String) extends AnyVal
  object JsonInString {
    def make[A](x: A)(implicit e: Encoder[A]): JsonInString = {
      new JsonInString(e(x).spaces2)
    }
  }

  type Context = Map[String, JsonInString]
  object Context {
    def empty: Context = Map.empty
  }

  private object Macros {
    import scala.reflect.macros.blackbox
    type Context[F[_]] = blackbox.Context { type PrefixType = LoggerImpl[F] }
    def info[F[_]](c: Context[F])(message: c.Expr[String]): c.Expr[F[Unit]] = {
      import c.universe._
      val tree = q"""
        ${c.prefix}.log2(self => (self.isInfoEnabled, marker => self.FSync.delay { self.underlying.info(marker, $message) }))
        """
      c.Expr[F[Unit]](tree)
    }
  }

  class LoggerImpl[F[_]](val underlying: org.slf4j.Logger, context: Context)(
    implicit FApplicativeLocal: ApplicativeLocal[F, Context],
    val FSync: Sync[F]
  ) extends Logger[F] {

    override def apply: LoggerImpl[F] = this

    private val marker: F[Marker] = FApplicativeLocal.ask.map { mdc =>
      val markers = mdc.toList.map {
        case (k, v) =>
          Markers.appendRaw(k, v.raw)
      }
      Markers.aggregate(markers: _*)
    }

    val isInfoEnabled: F[Boolean] = FSync.delay {
      underlying.isInfoEnabled
    }

    def log(isEnabled: F[Boolean], body: Marker => F[Unit]): F[Unit] =
      isEnabled.flatMap { isEnabled =>
        if (isEnabled) {
          use {
            marker.flatMap { marker =>
              body(marker)
            }
          }
        } else {
          FSync.unit
        }
      }

    def log2(f: LoggerImpl[F] => (F[Boolean], Marker => F[Unit])): F[Unit] = {
      val (isEnabled, body) = f(this)
      log(isEnabled, body)
    }

    import scala.language.experimental.macros

    def info(message: String): F[Unit] = macro Macros.info[F]
//      log(this,
//        isInfoEnabled,
//        marker => FSync.delay { underlying.info(marker, message) }
//      )

    def info(message: String, ex: Throwable): F[Unit] =
      log(
        isInfoEnabled,
        marker => FSync.delay { underlying.info(marker, message, ex) }
      )

    override def context[A](name: String,
                            value: A)(implicit e: Encoder[A]): Logger[F] =
      new LoggerImpl[F](
        underlying,
        context + ((name, JsonInString.make(value)))
      )

    override def context[A](
      map: Map[String, A]
    )(implicit e: Encoder[A]): Logger[F] =
      new LoggerImpl[F](
        underlying,
        context ++ map.mapValues(JsonInString.make(_))
      )

    override def use[A](inner: F[A]): F[A] =
      FApplicativeLocal.local(_ ++ context)(inner)
  }

//  private class LoggerBuilder2Impl[F[_]](
//    logger: Logger,
//    context: Map[String, String]
//  )(implicit FApplicativeLocal: ApplicativeLocal[F, Map[String, String]],
//    FSync: Sync[F])
//      extends LoggerBuilder2[F] {
//
//    private def log(body: LogstashMarker => Unit): F[Unit] =
//      for {
//        mdc <- FApplicativeLocal.ask
//        markers = mdc.toList.map { case (k, v) => Markers.appendRaw(k, v) }
//        _ <- FSync.delay {
//          body(Markers.aggregate(markers: _*))
//        }
//      } yield ()
//
//    override def msg(message: String): F[Unit] =
//      if (logger.isInfoEnabled) {
//        log { mdc =>
//          logger.info(mdc, message)
//        }
//      } else {
//        FSync.unit
//      }
//
//    override def msg(ex: Throwable)(message: String): F[Unit] = ???
//  }

  def make[F[_]](logger: org.slf4j.Logger)(
    implicit FApplicativeLocal: ApplicativeLocal[F, Context],
    FSync: Sync[F]
  ): Logger[F] = {
    new LoggerImpl(logger, Map())
  }

  def make[F[_]](name: String)(
    implicit FApplicativeLocal: ApplicativeLocal[F, Context],
    FSync: Sync[F]
  ): Logger[F] = {
    make(LoggerFactory.getLogger(name))
  }

  def make[F[_], T](implicit classTag: ClassTag[T],
                    FApplicativeLocal: ApplicativeLocal[F, Context],
                    FSync: Sync[F]): Logger[F] = {
    make(LoggerFactory.getLogger(classTag.runtimeClass))
  }
}

//trait Log[F[_]] {
//
//  def info[A](message: String): F[Unit]
//  def info[A](message: String, name: String, value: A)(
//    implicit e: Encoder[A]
//  ): F[Unit]
//  def info[A](message: String, m: Map[String, A])(
//    implicit e: Encoder[A]
//  ): F[Unit]
//  def info[A](message: String, ex: Throwable): F[Unit]
//  def info[A](message: String, name: String, value: A, ex: Throwable)(
//    implicit e: Encoder[A]
//  ): F[Unit]
//  def info[A](message: String, m: Map[String, A], ex: Throwable)(
//    implicit e: Encoder[A]
//  ): F[Unit]
//
//  def context[A, B](name: String, value: A)(inner: F[B])(
//    implicit e: Encoder[A]
//  ): F[B]
//  def context[A, B](map: Map[String, A])(inner: F[B])(
//    implicit e: Encoder[A]
//  ): F[B]
//}
//
//object Log {
//  private class LogImpl[F[_]](logger: Logger)(
//    implicit FApplicativeLocal: ApplicativeLocal[F, Map[String, String]],
//    FSync: Sync[F]
//  ) extends Log[F] {
//
//    private def log(body: LogstashMarker => Unit): F[Unit] =
//      for {
//        mdc <- FApplicativeLocal.ask
//        markers = mdc.toList.map { case (k, v) => Markers.appendRaw(k, v) }
//        _ <- FSync.delay {
//          body(Markers.aggregate(markers: _*))
//        }
//      } yield ()
//
//    override def context[A, B](name: String, value: A)(
//      inner: F[B]
//    )(implicit e: Encoder[A]): F[B] = {
//      if (logger.isTraceEnabled || logger.isDebugEnabled || logger.isInfoEnabled || logger.isWarnEnabled || logger.isErrorEnabled) {
//        FApplicativeLocal.local(_ + ((name, e(value).spaces2)))(inner)
//      } else {
//        inner
//      }
//    }
//
//    override def context[A, B](
//      map: Map[String, A]
//    )(inner: F[B])(implicit e: Encoder[A]): F[B] = {
//      if (logger.isTraceEnabled || logger.isDebugEnabled || logger.isInfoEnabled || logger.isWarnEnabled || logger.isErrorEnabled) {
//        FApplicativeLocal.local(map.foldLeft(_) {
//          case (m, (k, v)) => m + ((k, e(v).spaces2))
//        })(inner)
//      } else {
//        inner
//      }
//    }
//
//    def withContext[A, B, C](
//      name1: String,
//      value1: A,
//      name2: String,
//      value2: B
//    )(inner: F[C])(implicit e1: Encoder[A], e2: Encoder[B]): F[C] = {
//      if (logger.isTraceEnabled || logger.isDebugEnabled || logger.isInfoEnabled || logger.isWarnEnabled || logger.isErrorEnabled) {
//        FApplicativeLocal.local(
//          _ + ((name1, e1(value1).spaces2)) + ((name2, e2(value2).spaces2))
//        )(inner)
//      } else {
//        inner
//      }
//    }
//
//    override def info[A](message: String): F[Unit] =
//      if (logger.isInfoEnabled) {
//        log { mdc =>
//          logger.info(mdc, message)
//        }
//      } else {
//        FSync.unit
//      }
//
//    override def info[A](message: String, name: String, value: A)(
//      implicit e: Encoder[A]
//    ): F[Unit] =
//      if (logger.isInfoEnabled) {
//        withContext(name, value) {
//          log { mdc =>
//            logger.info(mdc, message)
//          }
//        }
//      } else {
//        FSync.unit
//      }
//
//    // only for Throwables for which you don't want to create Encoder, otherwise use `withContext`
//    override def info[A](message: String, ex: Throwable): F[Unit] =
//      if (logger.isInfoEnabled) {
//        log { mdc =>
//          logger.info(mdc, message, ex)
//        }
//      } else {
//        FSync.unit
//      }
//
//    // only for Throwables for which you don't want to create Encoder, otherwise use `withContext`
//    override def info[A](message: String,
//                         name: String,
//                         value: A,
//                         ex: Throwable)(implicit e: Encoder[A]): F[Unit] =
//      if (logger.isInfoEnabled) {
//        withContext(name, value) {
//          log { mdc =>
//            logger.info(mdc, message, ex)
//          }
//        }
//      } else {
//        FSync.unit
//      }
//  }
//
//  def make[F[_]](logger: Logger)(
//    implicit FApplicativeLocal: ApplicativeLocal[F, Map[String, String]],
//    FSync: Sync[F]
//  ): Log[F] = {
//    new LogImpl(logger)
//  }
//}

object MonixLog {
  def make(
    logger: org.slf4j.Logger
  )(taskLocalContext: TaskLocal[Logger.Context]): Logger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      Logger.make(logger)
    }
  }

  def make(
    name: String
  )(taskLocalContext: TaskLocal[Logger.Context]): Logger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      Logger.make(name)
    }
  }

  def make[T](
    taskLocalContext: TaskLocal[Logger.Context]
  )(implicit classTag: ClassTag[T]): Logger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      Logger.make
    }
  }
}
