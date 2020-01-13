package slf4cats.api

import cats.effect.Sync
import org.slf4j.Marker

trait ArgumentsBuilder[F[_]] {
  type Self <: LoggingContext[F]
  def withArg[A](
      name: String,
      value: => A,
  )(implicit logEncoder: LogEncoder[A]): Self
  def withComputed[A](
      name: String,
      value: F[A],
  )(implicit logEncoder: LogEncoder[A]): Self
  //def withArgs[A](map: Map[String, A], toJson: Option[A => String] = None): Self
}

trait LoggingContext[F[_]] extends StructuredLoggerArguments[F] {
  def use[A](inner: F[A]): F[A]
}

trait Logger[F[_]] extends StructuredLoggerArguments[F] {
  def info: LoggerInfo[F]
  def warn: LoggerWarn[F]
}

trait ContextLogger[F[_]] extends LoggingContext[F] with Logger[F] {
  type Self <: ContextLogger[F]
}

trait LogEncoder[A] {
  def encode(a: A): String
}

object LogEncoder {
  def apply[A](implicit e: LogEncoder[A]): LogEncoder[A] = e
}

trait LoggerCommand[F[_]] {

// could be made available if there's interest
//def isEnabled: F[Boolean]

  /** To be used by a macro, don't use this yourself */
  def withUnderlying(
      macroCallback: (Sync[F], org.slf4j.Logger) => (Marker => F[Unit]),
  ): F[Unit]
}

object LoggerCommand {
  private[api] object Macros {
    import scala.reflect.macros.blackbox
    type Context[F[_]] = blackbox.Context { type PrefixType = LoggerCommand[F] }

    def log[F[_]](
        c: Context[F],
    )(level: c.TermName, message: c.Expr[String]): c.Expr[F[Unit]] = {
      import c.universe._
      val tree =
        q"${c.prefix}.withUnderlying { case (fsync, underlying) => (marker => fsync.delay { underlying.$level(marker, $message) }) }"
      c.Expr[F[Unit]](tree)
    }

    def logThrowable[F[_]](c: Context[F])(
        level: c.TermName,
        message: c.Expr[String],
        throwable: c.Expr[Throwable],
    ): c.Expr[F[Unit]] = {
      import c.universe._
      val tree =
        q"${c.prefix}.withUnderlying { case (fsync, underlying) => (marker => fsync.delay { underlying.$level(marker, $message, $throwable) }) }"
      c.Expr[F[Unit]](tree)
    }

    def info[F[_]](c: Context[F])(message: c.Expr[String]): c.Expr[F[Unit]] =
      log(c)(c.universe.TermName("info"), message)

    def infoThrowable[F[_]](
        c: Context[F],
    )(message: c.Expr[String], throwable: c.Expr[Throwable]): c.Expr[F[Unit]] =
      logThrowable(c)(c.universe.TermName("info"), message, throwable)

    def warn[F[_]](c: Context[F])(message: c.Expr[String]): c.Expr[F[Unit]] =
      log(c)(c.universe.TermName("warn"), message)

    def warnThrowable[F[_]](
        c: Context[F],
    )(message: c.Expr[String], throwable: c.Expr[Throwable]): c.Expr[F[Unit]] =
      logThrowable(c)(c.universe.TermName("warn"), message, throwable)
  }
}

abstract class LoggerInfo[F[_]]() extends LoggerCommand[F] {
  def apply(message: String): F[Unit] = macro LoggerCommand.Macros.info[F]
  def apply(message: String, throwable: Throwable): F[Unit] =
    macro LoggerCommand.Macros.infoThrowable[F]
}

abstract class LoggerWarn[F[_]]() extends LoggerCommand[F] {
  def apply(message: String): F[Unit] = macro LoggerCommand.Macros.warn[F]
  def apply(message: String, throwable: Throwable): F[Unit] =
    macro LoggerCommand.Macros.warnThrowable[F]
}
