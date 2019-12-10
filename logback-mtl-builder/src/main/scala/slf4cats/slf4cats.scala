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
  type Self <: Contexter[F]
  def context[A](name: String, value: A)(implicit e: Encoder[A]): Self
  def context[A](map: Map[String, A])(implicit e: Encoder[A]): Self
  def use[A](inner: F[A]): F[A]
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
    FSync: Sync[F]
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
  }

  def make[F[_]](implicit FApplicativeLocal: ApplicativeLocal[F, Context],
                 FSync: Sync[F]): Contexter[F] = {
    new ContexterImpl(Map())
  }

}

trait Logger[F[_]] {
  type Self <: Logger[F]
  def context[A](name: String, value: A)(implicit e: Encoder[A]): Self
  def context[A](map: Map[String, A])(implicit e: Encoder[A]): Self
  def info: LoggerInfo[F]
}

object Logger {

  private class LoggerImpl[F[_]](
    underlying: org.slf4j.Logger,
    context: Map[String, (Encoder[Any], Any)]
  )(implicit FSync: Sync[F],
    FApplicativeAsk: ApplicativeAsk[F, Contexter.Context])
      extends Logger[F] {

    override type Self = Logger[F]

    override def info: LoggerInfo[F] =
      new LoggerInfo[F](underlying, context)

    override def context[A](name: String,
                            value: A)(implicit e: Encoder[A]): Logger[F] =
      new LoggerImpl[F](
        underlying,
        context + ((name, (e.asInstanceOf[Encoder[Any]], value)))
      )

    override def context[A](
      map: Map[String, A]
    )(implicit e: Encoder[A]): Logger[F] =
      new LoggerImpl[F](
        underlying,
        context ++ map.mapValues(v => (e.asInstanceOf[Encoder[Any]], v))
      )

  }

  def make[F[_]](logger: org.slf4j.Logger)(
    implicit FSync: Sync[F],
    FApplicativeAsk: ApplicativeAsk[F, Contexter.Context]
  ): Logger[F] = {
    new LoggerImpl(logger, Map.empty)
  }

  def make[F[_]](name: String)(
    implicit FSync: Sync[F],
    FApplicativeAsk: ApplicativeAsk[F, Contexter.Context]
  ): Logger[F] = {
    make(LoggerFactory.getLogger(name))
  }

  def make[F[_], T](contexter: Contexter[F])(
    implicit classTag: ClassTag[T],
    FSync: Sync[F],
    FApplicativeAsk: ApplicativeAsk[F, Contexter.Context]
  ): Logger[F] = {
    make(LoggerFactory.getLogger(classTag.runtimeClass))
  }
}

sealed class LoggerCommand[F[_]] private[slf4cats] (
  underlying: org.slf4j.Logger,
  context: Map[String, (Encoder[Any], Any)]
)(implicit
  FSync: Sync[F],
  FApplicativeAsk: ApplicativeAsk[F, Contexter.Context]) {

  import Contexter._

  protected val marker: F[Marker] = FApplicativeAsk.ask.map { mdc =>
    val contextEncoded = context.mapValues {
      case (e, v) => JsonInString.make(v)(e)
    }
    val markers = (mdc ++ contextEncoded).toList.map {
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
        marker.flatMap { marker =>
          body(marker)
        }
      } else {
        FSync.unit
      }
    }
  }

}

trait ContextLogger[F[_]] extends Contexter[F] with Logger[F] {
  type Self <: ContextLogger[F]
}

object ContextLogger {

  import Contexter._

  private class ContextLoggerImpl[F[_]](
    underlying: org.slf4j.Logger,
    context: Map[String, (Encoder[Any], Any)]
  )(implicit FApplicativeLocal: ApplicativeLocal[F, Context], FSync: Sync[F])
      extends ContextLogger[F] {

    override type Self = ContextLogger[F]

    override def info: LoggerInfo[F] =
      new LoggerInfo[F](underlying, context)

    override def context[A](name: String,
                            value: A)(implicit e: Encoder[A]): Self =
      new ContextLoggerImpl[F](
        underlying,
        context + ((name, (e.asInstanceOf[Encoder[Any]], value)))
      )

    override def context[A](map: Map[String, A])(implicit e: Encoder[A]): Self =
      new ContextLoggerImpl[F](
        underlying,
        context ++ map.mapValues(v => (e.asInstanceOf[Encoder[Any]], v))
      )

    override def use[A](inner: F[A]): F[A] =
      FApplicativeLocal.local(_ ++ context.mapValues {
        case (e, v) => JsonInString.make(v)(e)
      })(inner)

  }

  def make[F[_]](logger: org.slf4j.Logger)(
    implicit FSync: Sync[F],
    FApplicativeLocal: ApplicativeLocal[F, Contexter.Context]
  ): ContextLogger[F] = {
    new ContextLoggerImpl[F](logger, Map.empty)
  }

  def make[F[_]](name: String)(
    implicit FSync: Sync[F],
    FApplicativeAsk: ApplicativeLocal[F, Contexter.Context]
  ): ContextLogger[F] = {
    make(LoggerFactory.getLogger(name))
  }

  def make[F[_], T](
    implicit classTag: ClassTag[T],
    FSync: Sync[F],
    FApplicativeAsk: ApplicativeLocal[F, Contexter.Context]
  ): ContextLogger[F] = {
    make(LoggerFactory.getLogger(classTag.runtimeClass))
  }

}

object LoggerCommand {
  private[slf4cats] object Macros {
    import scala.reflect.macros.blackbox
    type Context[F[_]] = blackbox.Context { type PrefixType = LoggerCommand[F] }
  }
}

class LoggerInfo[F[_]] private[slf4cats] (
  underlying: org.slf4j.Logger,
  context: Map[String, (Encoder[Any], Any)]
)(implicit
  FSync: Sync[F],
  FApplicativeAsk: ApplicativeAsk[F, Contexter.Context])
    extends LoggerCommand(underlying, context) {
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
