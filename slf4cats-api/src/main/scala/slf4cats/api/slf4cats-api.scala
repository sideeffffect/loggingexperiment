package slf4cats.api

import cats.effect.Sync
import org.slf4j.Marker

trait ContextManager[F[_]] {
  type Self <: ContextManager[F]
  def withArg[A](name: String,
                 value: => A,
                 toJson: Option[A => String] = None): Self
  def withComputed[A](name: String,
                      value: F[A],
                      toJson: Option[A => String] = None): Self
  def withArgs[A](map: Map[String, A], toJson: Option[A => String] = None): Self
  def use[A](inner: F[A]): F[A]
}

trait Logger[F[_]] {
  type Self <: Logger[F]
  def withArg[A](name: String,
                 value: => A,
                 toJson: Option[A => String] = None): Self
  def withComputed[A](name: String,
                      value: F[A],
                      toJson: Option[A => String] = None): Self
  def withArgs[A](map: Map[String, A], toJson: Option[A => String] = None): Self
  def info: LoggerInfo[F]
}

trait ContextLogger[F[_]] extends ContextManager[F] with Logger[F] {
  type Self <: ContextLogger[F]
}

trait LoggerCommand[F[_]] {

// could be made available if there's interest
//def isEnabled: F[Boolean]

  def withUnderlying(
    macroCallback: (Sync[F], org.slf4j.Logger) => (Marker => F[Unit])
  ): F[Unit]
}

object LoggerCommand {
  private[api] object Macros {
    import scala.reflect.macros.blackbox
    type Context[F[_]] = blackbox.Context { type PrefixType = LoggerCommand[F] }

    def log[F[_]](c: Context[F])(level: c.TermName,
                                 message: c.Expr[String]): c.Expr[F[Unit]] = {
      import c.universe._
      val tree =
        q"${c.prefix}.withUnderlying { case (fsync, underlying) => (marker => fsync.delay { underlying.$level(marker, $message) }) }"
      c.Expr[F[Unit]](tree)
    }

    def logThrowable[F[_]](c: Context[F])(
      level: c.TermName,
      message: c.Expr[String],
      throwable: c.Expr[Throwable]
    ): c.Expr[F[Unit]] = {
      import c.universe._
      val tree =
        q"${c.prefix}.withUnderlying { case (fsync, underlying) => (marker => fsync.delay { underlying.$level(marker, $message, $throwable) }) }"
      c.Expr[F[Unit]](tree)
    }

    def info[F[_]](c: Context[F])(message: c.Expr[String]): c.Expr[F[Unit]] =
      log(c)(c.universe.TermName("info"), message)

    def infoThrowable[F[_]](
      c: Context[F]
    )(message: c.Expr[String], throwable: c.Expr[Throwable]): c.Expr[F[Unit]] =
      logThrowable(c)(c.universe.TermName("info"), message, throwable)
  }
}

abstract class LoggerInfo[F[_]]() extends LoggerCommand[F] {
  def apply(message: String): F[Unit] = macro LoggerCommand.Macros.info[F]
  def apply(message: String, throwable: Throwable): F[Unit] =
    macro LoggerCommand.Macros.infoThrowable[F]
}
