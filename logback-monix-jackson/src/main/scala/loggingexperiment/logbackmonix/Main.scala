package loggingexperiment.logbackmonix

import cats.effect._
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import monix.eval._
import monix.execution.Scheduler
import net.logstash.logback.argument.StructuredArguments
import net.logstash.logback.marker.{LogstashMarker, Markers}
import org.slf4j.{Logger, LoggerFactory}

final case class A(x: Int, y: String)
final case class B(a: A, b: Boolean)

trait Log {
  def info(format: String, xName: String, x: Any): Task[Unit]
  def info(format: String,
           xName: String,
           x: Any,
           yName: String,
           y: Any): Task[Unit]
  def addContext(xName: String, x: Any)(
    implicit sch: Scheduler
  ): Resource[Task, Unit]
}

object Log {
  private class LogImpl(logger: Logger, mdc: TaskLocal[Map[String, List[Any]]])
      extends Log {

    private val jackson = new ObjectMapper()

    jackson.setVisibility(PropertyAccessor.ALL, Visibility.NONE)
    jackson.setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
//    jackson.setVisibility(
//      jackson
//        .getSerializationConfig()
//        .getDefaultVisibilityChecker()
//        .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
//        .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
//        .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
//        .withCreatorVisibility(JsonAutoDetect.Visibility.NONE)
//    )

    private def log(body: LogstashMarker => Unit): Task[Unit] =
      for {
        mdc <- mdc.read
        mdcNormalized = mdc.toList.map {
          case (k, v :: _) => (k, jackson.writeValueAsString(v))
        }
        markers = mdcNormalized.map { case (k, v) => Markers.appendRaw(k, v) }
        _ <- Task.delay {
          body(Markers.aggregate(markers: _*))
        }
      } yield ()

    override def addContext(xName: String, x: Any)(
      implicit sch: Scheduler
    ): Resource[Task, Unit] = {
      Resource.make {
        for {
          ctx <- mdc.read
          ctxNew = ctx.get(xName) match {
            case Some(list) =>
              ctx + ((xName, x :: list))
            case None =>
              ctx + ((xName, x :: Nil))
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

    override def info(format: String, xName: String, x: Any): Task[Unit] =
      log { mdc =>
        logger.info(
          mdc,
          format,
          StructuredArguments.raw(xName, jackson.writeValueAsString(x)),
        )
      }

    override def info(format: String,
                      xName: String,
                      x: Any,
                      yName: String,
                      y: Any): Task[Unit] =
      log { mdc =>
        logger.info(
          mdc,
          format,
          StructuredArguments.raw(xName, jackson.writeValueAsString(x)),
          StructuredArguments.raw(yName, jackson.writeValueAsString(y)): Any,
        )
      }
  }

  def make(logger: Logger): Task[Log] = {
    for {
      mdc <- TaskLocal(Map.empty[String, List[Any]])
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
      _ <- logger.addContext("yyy", A(567, "YYYYYYYYYYYY")).use { _: Unit =>
        for {
          _ <- logger.info("Hello {}", "o", o)
        } yield ()
      }
      _ <- logger.info("Hello {}", "o", o)
      _ <- logger.info("Hello2 {} and {}", "x", 123, "o", o)
    } yield ()
  }

}
