package slf4cats.impl

import cats._
import cats.effect._
import cats.implicits._
import cats.mtl._
import net.logstash.logback.marker.Markers
import org.slf4j.{LoggerFactory, Marker}
import slf4cats.api._

import scala.reflect.ClassTag
import scala.util.control.NonFatal

object ContextLogger {

  object JsonInString {
    private[ContextLogger] def make[F[_], A](
        toJson: A => String,
    )(x: A)(implicit F: Sync[F]): F[String] = {
      F.delay {
          toJson(x)
        }
        .recover { case NonFatal(e) => "\"<" + e + ">\"" }
    }
  }

  type Context[F[_]] = Map[String, F[String]]
  object Context {
    def empty[F[_]]: Context[F] = Map.empty
  }

  private def mapSequence[F[_], K, V](
      m: Map[K, F[V]],
  )(implicit FApplicative: Applicative[F]): F[Map[K, V]] = {
    m.foldLeft(FApplicative.pure(Map.empty[K, V])) {
      case (fm, (k, fv)) =>
        FApplicative.tuple2(fm, fv).map {
          case (m, v) =>
            m + ((k, v))
        }
    }
  }

  private trait LoggerCommandImpl[F[_]] {

    def underlying: org.slf4j.Logger

    def localContext: Map[String, F[F[String]]]

    implicit def FSync: Sync[F]

    implicit def FApplicativeAsk: ApplicativeAsk[F, Context[F]]

    private val marker: F[Marker] = for {
      context1 <- FApplicativeAsk.ask
      context2 <- mapSequence(localContext)
      union <- mapSequence(context1 ++ context2)
      markers = union.toList.map {
        case (k, v) =>
          Markers.appendRaw(k, v)
      }
      result = Markers.aggregate(markers: _*)
    } yield result

    protected def isEnabled: F[Boolean]

    def withUnderlying(
        macroCallback: (Sync[F], org.slf4j.Logger) => (Marker => F[Unit]),
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

  private class LoggerInfoImpl[F[_]](
      val underlying: org.slf4j.Logger,
      val localContext: Map[String, F[F[String]]],
  )(
      implicit
      val FSync: Sync[F],
      val FApplicativeAsk: ApplicativeAsk[F, Context[F]],
  ) extends LoggerInfo[F]
      with LoggerCommandImpl[F] {

    override protected val isEnabled: F[Boolean] = FSync.delay {
      underlying.isInfoEnabled
    }
  }

  private class LoggerWarnImpl[F[_]](
      val underlying: org.slf4j.Logger,
      val localContext: Map[String, F[F[String]]],
  )(
      implicit
      val FSync: Sync[F],
      val FApplicativeAsk: ApplicativeAsk[F, Context[F]],
  ) extends LoggerWarn[F]
      with LoggerCommandImpl[F] {

    override protected val isEnabled: F[Boolean] = FSync.delay {
      underlying.isWarnEnabled
    }
  }

  private class ContextLoggerImpl[F[_]](
      underlying: org.slf4j.Logger,
      context: Map[String, F[F[String]]],
  )(
      implicit FApplicativeLocal: ApplicativeLocal[F, Context[F]],
      FAsync: Async[F],
  ) extends ContextLogger[F] {

    override type Self = ContextLogger[F]

    override def info: LoggerInfo[F] =
      new LoggerInfoImpl[F](underlying, context)

    override def warn: LoggerWarn[F] =
      new LoggerWarnImpl[F](underlying, context)

    override def withArg[A](
        name: String,
        value: => A,
    )(implicit logEncoder: LogEncoder[A]): ContextLogger[F] =
      withComputed(
        name,
        FAsync.delay {
          value
        },
      )

    override def withComputed[A](
        name: String,
        value: F[A],
    )(implicit logEncoder: LogEncoder[A]): ContextLogger[F] = {
      val memoizedJson =
        Async.memoize(
          value.flatMap(JsonInString.make(logEncoder.encode)(_)),
        )
      new ContextLoggerImpl[F](
        underlying,
        context + ((name, memoizedJson)),
      )
    }

    override def withArgs[A](
        map: Map[String, A],
    )(implicit logEncoder: LogEncoder[A]): ContextLogger[F] = {
      new ContextLoggerImpl[F](
        underlying,
        context ++ map
          .mapValues(v =>
            Async.memoize(
              JsonInString
                .make(logEncoder.encode)(v),
            ),
          ),
      )
    }

    override def use[A](inner: F[A]): F[A] = {
      mapSequence(context).flatMap { contextMemoized =>
        FApplicativeLocal.local(_ ++ contextMemoized)(inner)
      }
    }
  }

  def fromLogger[F[_]](
      logger: org.slf4j.Logger,
  )(
      implicit FAsync: Async[F],
      FApplicativeLocal: ApplicativeLocal[F, Context[F]],
  ): ContextLogger[F] = {
    new ContextLoggerImpl[F](
      logger,
      Map.empty,
    )
  }

  def fromName[F[_]](name: String)(
      implicit FAsync: Async[F],
      FApplicativeAsk: ApplicativeLocal[F, Context[F]],
  ): ContextLogger[F] = {
    fromLogger(LoggerFactory.getLogger(name))
  }

  def fromClass[F[_], T]()(
      implicit classTag: ClassTag[T],
      FAsync: Async[F],
      FApplicativeAsk: ApplicativeLocal[F, Context[F]],
  ): ContextLogger[F] = {
    fromLogger(LoggerFactory.getLogger(classTag.runtimeClass))
  }

}
