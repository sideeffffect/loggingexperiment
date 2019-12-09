package slf4cats

import cats.effect._
import cats.implicits._
import cats.mtl._
import io.circe.Encoder
import io.circe.generic.auto._
import net.logstash.logback.marker.Markers
import org.slf4j.{LoggerFactory, Marker}

import scala.language.higherKinds
import scala.reflect.ClassTag

trait Contexter[F[_]] {
  import Contexter._
  type Self <: Contexter[F]
  def context[A](name: String, value: A)(implicit e: Encoder[A]): Self
  def context[A](map: Map[String, A])(implicit e: Encoder[A]): Self
  def use[A](inner: F[A]): F[A]
  def get: F[Context]
}

object Contexter {

  class JsonInString private (private[slf4cats] val raw: String) extends AnyVal
  object JsonInString {
    def make[A](x: A)(implicit e: Encoder[A]): JsonInString = {
      new JsonInString(e(x).spaces2)
    }
  }

  type Context = Map[String, JsonInString]
  object Context {
    def empty: Context = Map.empty
  }

  private class ContexterImpl[F[_]](context: Map[String, (Encoder[Any], Any)])(
    implicit FApplicativeLocal: ApplicativeLocal[F, Context],
    val FSync: Sync[F]
  ) extends Contexter[F] {
    override type Self = Contexter[F]

    override def context[A](name: String,
                            value: A)(implicit e: Encoder[A]): Contexter[F] =
      new ContexterImpl[F](
        context + ((name, (e.asInstanceOf[Encoder[Any]], value)))
      )

    override def context[A](
      map: Map[String, A]
    )(implicit e: Encoder[A]): Contexter[F] =
      new ContexterImpl[F](
        context ++ map.mapValues((e.asInstanceOf[Encoder[Any]], _))
      )

    override def use[A](inner: F[A]): F[A] =
      FApplicativeLocal.local(_ ++ context.mapValues {
        case (e, v) => JsonInString.make(v)(e)
      })(inner)

    override def get: F[Context] = FApplicativeLocal.ask
  }

  def make[F[_]](implicit FApplicativeLocal: ApplicativeLocal[F, Context],
                 FSync: Sync[F]): Contexter[F] = {
    new ContexterImpl(Map())
  }

}

trait Logger[F[_]] extends Contexter[F] {
  type Self <: Logger[F]
  def info: LoggerInfo[F]
}

object Logger {

  import Contexter._

  private class LoggerImpl[F[_]](
    underlying: org.slf4j.Logger,
    contexter: Contexter[F]
  )(implicit FSync: Sync[F])
      extends Logger[F] {

    override type Self = Logger[F]

    override def info: LoggerInfo[F] =
      new LoggerInfo[F](this, underlying, contexter)

    override def context[A](name: String,
                            value: A)(implicit e: Encoder[A]): Logger[F] =
      new LoggerImpl[F](underlying, contexter.context(name, value))

    override def context[A](
      map: Map[String, A]
    )(implicit e: Encoder[A]): Logger[F] =
      new LoggerImpl[F](underlying, contexter.context(map))

    override def use[A](inner: F[A]): F[A] = contexter.use(inner)

    override def get: F[Context] = contexter.get
  }

  def make[F[_]](logger: org.slf4j.Logger, contexter: Contexter[F])(
    implicit FSync: Sync[F]
  ): Logger[F] = {
    new LoggerImpl(logger, contexter)
  }

  def make[F[_]](name: String, contexter: Contexter[F])(
    implicit FSync: Sync[F]
  ): Logger[F] = {
    make(LoggerFactory.getLogger(name), contexter)
  }

  def make[F[_], T](contexter: Contexter[F])(implicit classTag: ClassTag[T],
                                             FSync: Sync[F]): Logger[F] = {
    make(LoggerFactory.getLogger(classTag.runtimeClass), contexter)
  }
}

sealed class LoggerCommand[F[_]] private[slf4cats] (
  logger: Logger[F],
  underlying: org.slf4j.Logger,
  contexter: Contexter[F]
)(implicit
  FSync: Sync[F]) {

  protected val marker: F[Marker] = contexter.get.map { mdc =>
    val markers = mdc.toList.map {
      case (k, v) =>
        Markers.appendRaw(k, v.raw)
    }
    Markers.aggregate(markers: _*)
  }

  /** only to be used used by macro */
  def withUnderlying(
    macroCallback: (Sync[F], org.slf4j.Logger) => (F[Boolean],
                                                   Marker => F[Unit])
  ): F[Unit] = {
    val (isEnabled, body) = macroCallback(FSync, underlying)
    isEnabled.flatMap { isEnabled =>
      if (isEnabled) {
        logger.use {
          marker.flatMap { marker =>
            body(marker)
          }
        }
      } else {
        FSync.unit
      }
    }
  }

}

object LoggerCommand {
  private[slf4cats] object Macros {
    import scala.reflect.macros.blackbox
    type Context[F[_]] = blackbox.Context { type PrefixType = LoggerCommand[F] }
  }
}

class LoggerInfo[F[_]] private[slf4cats] (
  logger: Logger[F],
  underlying: org.slf4j.Logger,
  contexter: Contexter[F]
)(implicit
  FSync: Sync[F])
    extends LoggerCommand(logger, underlying, contexter) {
  import scala.language.experimental.macros
  def apply(message: String): F[Unit] = macro LoggerInfo.Macros.info[F]
  def apply(message: String, throwable: Throwable): F[Unit] =
    macro LoggerInfo.Macros.infoThrowable[F]
}

object LoggerInfo {

  private[LoggerInfo] object Macros {
    import LoggerCommand.Macros._

    def info[F[_]](c: Context[F])(message: c.Expr[String]): c.Expr[F[Unit]] = {
      import c.universe._
      val tree =
        q"${c.prefix}.withUnderlying { case (fsync, underlying) => (fsync.delay { underlying.isInfoEnabled }, marker => fsync.delay { underlying.info(marker, $message) }) }"
      c.Expr[F[Unit]](tree)
    }

    def infoThrowable[F[_]](c: Context[F])(
      message: c.Expr[String],
      throwable: c.Expr[Throwable]
    ): c.Expr[F[Unit]] = {
      import c.universe._
      val tree =
        q"${c.prefix}.withUnderlying { case (fsync, underlying) => (fsync.delay { underlying.isInfoEnabled }, marker => fsync.delay { underlying.info(marker, $message, $throwable) }) }"
      c.Expr[F[Unit]](tree)
    }
  }

}
