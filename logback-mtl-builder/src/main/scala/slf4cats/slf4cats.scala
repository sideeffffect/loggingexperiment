package slf4cats

import cats._
import cats.effect._
import cats.implicits._
import cats.mtl._
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import net.logstash.logback.marker.Markers
import org.slf4j.{LoggerFactory, Marker}
import slf4cats.ContextManager.JsonInString

import scala.language.higherKinds
import scala.reflect.ClassTag

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

object ContextManager {

  private[slf4cats] class JsonInString private (
    private[slf4cats] val raw: String
  ) extends AnyVal

  object JsonInString {

    val defaultToJson: Any => String = {
      val jackson = new ObjectMapper()
      jackson.setVisibility(PropertyAccessor.ALL, Visibility.NONE)
      jackson.setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
      x =>
        jackson.writeValueAsString(x)
    }

    private[slf4cats] def make[F[_], A](
      toJson: A => String
    )(x: A)(implicit F: Sync[F]): F[JsonInString] = {
      F.delay { new JsonInString(toJson(x)) }
    }
  }

  type Context[F[_]] = Map[String, F[JsonInString]]
  object Context {
    def empty[F[_]]: Context[F] = Map.empty
  }

  private class ContextManagerImpl[F[_]](
    localContext: Map[String, F[F[JsonInString]]],
    toJsonGlobal: Any => String,
  )(implicit FApplicativeLocal: ApplicativeLocal[F, Context[F]],
    FAsync: Async[F])
      extends ContextManager[F] {

    override type Self = ContextManager[F]

    override def withArg[A](
      name: String,
      value: => A,
      toJson: Option[A => String] = None
    ): ContextManager[F] =
      withComputed(name, FAsync.delay {
        value
      })

    override def withComputed[A](
      name: String,
      value: F[A],
      toJson: Option[A => String] = None
    ): ContextManager[F] = {
      val memoizedJson =
        Async.memoize(
          value.flatMap(JsonInString.make(toJson.getOrElse(toJsonGlobal))(_))
        )
      new ContextManagerImpl[F](
        localContext + ((name, memoizedJson)),
        toJsonGlobal
      )
    }

    override def withArgs[A](
      map: Map[String, A],
      toJson: Option[A => String] = None
    ): ContextManager[F] =
      new ContextManagerImpl[F](
        localContext ++ map
          .mapValues(
            v =>
              Async
                .memoize(JsonInString.make(toJson.getOrElse(toJsonGlobal))(v))
          ),
        toJsonGlobal
      )

    override def use[A](inner: F[A]): F[A] = {
      mapSequence(localContext).flatMap { contextMemoized =>
        FApplicativeLocal.local(_ ++ contextMemoized)(inner)
      }
    }
  }

  private[slf4cats] def mapSequence[F[_], K, V](
    m: Map[K, F[V]]
  )(implicit FApplicative: Applicative[F]): F[Map[K, V]] = {
    m.foldLeft(FApplicative.pure(Map.empty[K, V])) {
      case (m, (k, fv)) =>
        FApplicative.tuple2(m, fv).map {
          case (m, v) =>
            m + ((k, v))
        }
    }
  }

  def make[F[_]](toJson: Option[Any => String] = None)(
    implicit FApplicativeLocal: ApplicativeLocal[F, Context[F]],
    FAsync: Async[F]
  ): ContextManager[F] = {
    new ContextManagerImpl(Map(), toJson.getOrElse(JsonInString.defaultToJson))
  }

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

object Logger {

  private class LoggerImpl[F[_]](
    underlying: org.slf4j.Logger,
    localContext: ContextManager.Context[F],
    toJsonGlobal: Any => String
  )(implicit FSync: Sync[F],
    FApplicativeAsk: ApplicativeAsk[F, ContextManager.Context[F]])
      extends Logger[F] {

    override type Self = Logger[F]

    override def info: LoggerInfo[F] =
      new LoggerInfo[F](underlying, localContext.mapValues(FSync.pure))

    override def withArg[A](name: String,
                            value: => A,
                            toJson: Option[A => String] = None): Logger[F] =
      withComputed(name, FSync.delay { value })

    override def withComputed[A](
      name: String,
      value: F[A],
      toJson: Option[A => String] = None
    ): Logger[F] = {
      val json = value.flatMap(
        ContextManager.JsonInString.make(toJson.getOrElse(toJsonGlobal))(_)
      )
      new LoggerImpl[F](underlying, localContext + ((name, json)), toJsonGlobal)
    }

    override def withArgs[A](map: Map[String, A],
                             toJson: Option[A => String] = None): Logger[F] =
      new LoggerImpl[F](
        underlying,
        localContext ++ map
          .mapValues(
            v =>
              ContextManager.JsonInString
                .make(toJson.getOrElse(toJsonGlobal))(v)
          ),
        toJsonGlobal
      )

  }

  def fromLogger[F[_]](logger: org.slf4j.Logger,
                       toJson: Option[Any => String] = None)(
    implicit FAsync: Async[F],
    FApplicativeAsk: ApplicativeAsk[F, ContextManager.Context[F]]
  ): Logger[F] = {
    new LoggerImpl(
      logger,
      Map.empty,
      toJson.getOrElse(JsonInString.defaultToJson)
    )
  }

  def fromName[F[_]](name: String, toJson: Option[Any => String] = None)(
    implicit FAsync: Async[F],
    FApplicativeAsk: ApplicativeAsk[F, ContextManager.Context[F]]
  ): Logger[F] = {
    fromLogger(LoggerFactory.getLogger(name), toJson)
  }

  def fromClass[F[_], T](toJson: Option[Any => String] = None)(
    implicit classTag: ClassTag[T],
    FAsync: Async[F],
    FApplicativeAsk: ApplicativeAsk[F, ContextManager.Context[F]]
  ): Logger[F] = {
    fromLogger(LoggerFactory.getLogger(classTag.runtimeClass), toJson)
  }
}

trait ContextLogger[F[_]] extends ContextManager[F] with Logger[F] {
  type Self <: ContextLogger[F]
}

object ContextLogger {

  import ContextManager._

  private class ContextLoggerImpl[F[_]](
    underlying: org.slf4j.Logger,
    context: Map[String, F[F[JsonInString]]],
    toJsonGlobal: Any => String
  )(implicit FApplicativeLocal: ApplicativeLocal[F, Context[F]],
    FAsync: Async[F])
      extends ContextLogger[F] {

    override type Self = ContextLogger[F]

    override def info: LoggerInfo[F] =
      new LoggerInfo[F](underlying, context)

    override def withArg[A](
      name: String,
      value: => A,
      toJson: Option[A => String] = None
    ): ContextLogger[F] =
      withComputed(name, FAsync.delay {
        value
      })

    override def withComputed[A](
      name: String,
      value: F[A],
      toJson: Option[A => String] = None
    ): ContextLogger[F] = {
      val memoizedJson =
        Async.memoize(
          value.flatMap(JsonInString.make(toJson.getOrElse(toJsonGlobal))(_))
        )
      new ContextLoggerImpl[F](
        underlying,
        context + ((name, memoizedJson)),
        toJsonGlobal
      )
    }

    override def withArgs[A](
      map: Map[String, A],
      toJson: Option[A => String] = None
    ): ContextLogger[F] =
      new ContextLoggerImpl[F](
        underlying,
        context ++ map
          .mapValues(
            v =>
              Async.memoize(
                ContextManager.JsonInString
                  .make(toJson.getOrElse(toJsonGlobal))(v)
            )
          ),
        toJsonGlobal
      )

    override def use[A](inner: F[A]): F[A] = {
      mapSequence(context).flatMap { contextMemoized =>
        FApplicativeLocal.local(_ ++ contextMemoized)(inner)
      }
    }
  }

  def fromLogger[F[_]](logger: org.slf4j.Logger,
                       toJson: Option[Any => String] = None)(
    implicit FAsync: Async[F],
    FApplicativeLocal: ApplicativeLocal[F, ContextManager.Context[F]]
  ): ContextLogger[F] = {
    new ContextLoggerImpl[F](
      logger,
      Map.empty,
      toJson.getOrElse(JsonInString.defaultToJson)
    )
  }

  def fromName[F[_]](name: String, toJson: Option[Any => String] = None)(
    implicit FAsync: Async[F],
    FApplicativeAsk: ApplicativeLocal[F, ContextManager.Context[F]]
  ): ContextLogger[F] = {
    fromLogger(LoggerFactory.getLogger(name), toJson)
  }

  def fromClass[F[_], T](toJson: Option[Any => String] = None)(
    implicit classTag: ClassTag[T],
    FAsync: Async[F],
    FApplicativeAsk: ApplicativeLocal[F, ContextManager.Context[F]]
  ): ContextLogger[F] = {
    fromLogger(LoggerFactory.getLogger(classTag.runtimeClass), toJson)
  }

}

abstract class LoggerCommand[F[_]](
  underlying: org.slf4j.Logger,
  localContext: Map[String, F[F[ContextManager.JsonInString]]]
)(implicit
  FSync: Sync[F],
  FApplicativeAsk: ApplicativeAsk[F, ContextManager.Context[F]]) {

  import ContextManager._

  private val marker: F[Marker] = for {
    context1 <- FApplicativeAsk.ask
    context2 <- mapSequence(localContext)
    union <- mapSequence(context1 ++ context2)
    markers = union.toList.map {
      case (k, v) =>
        Markers.appendRaw(k, v.raw)
    }
    result = Markers.aggregate(markers: _*)
  } yield result

  protected def isEnabled
    : F[Boolean] // could be made public if there's interest

  /** only to be used used by macro */
  def withUnderlying(
    macroCallback: (Sync[F], org.slf4j.Logger) => (Marker => F[Unit])
  ): F[Unit] = {
    val body = macroCallback(FSync, underlying)
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

object LoggerCommand {
  private[slf4cats] object Macros {
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
  }
}

class LoggerInfo[F[_]](
  underlying: org.slf4j.Logger,
  tmpContext: Map[String, F[F[ContextManager.JsonInString]]]
)(implicit
  FSync: Sync[F],
  FApplicativeAsk: ApplicativeAsk[F, ContextManager.Context[F]])
    extends LoggerCommand(underlying, tmpContext) {

  import scala.language.experimental.macros

  override protected val isEnabled: F[Boolean] = FSync.delay {
    underlying.isInfoEnabled
  }

  def apply(message: String): F[Unit] = macro LoggerInfo.Macros.info[F]
  def apply(message: String, throwable: Throwable): F[Unit] =
    macro LoggerInfo.Macros.infoThrowable[F]
}

object LoggerInfo {

  private[LoggerInfo] object Macros {
    import LoggerCommand.Macros._

    def info[F[_]](c: Context[F])(message: c.Expr[String]): c.Expr[F[Unit]] =
      log(c)(c.universe.TermName("info"), message)

    def infoThrowable[F[_]](
      c: Context[F]
    )(message: c.Expr[String], throwable: c.Expr[Throwable]): c.Expr[F[Unit]] =
      logThrowable(c)(c.universe.TermName("info"), message, throwable)
  }

}
