package loggingexperiment.logbackmtl

import java.security.InvalidParameterException

import cats.effect._
import com.olegpy.meow.monix._
import io.circe.generic.auto._
import monix.eval._
import monix.execution.Scheduler
import slf4cats._

import scala.language.higherKinds
import scala.reflect.ClassTag

object MonixLog {
  def make(
    logger: org.slf4j.Logger
  )(taskLocalContext: TaskLocal[Contexter.Context]): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.make(logger)
    }
  }

  def make(
    name: String
  )(taskLocalContext: TaskLocal[Contexter.Context]): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.make(name)
    }
  }

  def make[T](
    taskLocalContext: TaskLocal[Contexter.Context]
  )(implicit classTag: ClassTag[T]): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.make
    }
  }
}

object Main extends TaskApp {

  final case class A(x: Int, y: String)

  final case class B(a: A, b: Boolean)

  private val o = B(A(123, "Hello"), b = true)

  override def run(args: List[String]): Task[ExitCode] =
    init(scheduler)
      .map(_ => ExitCode.Success)
      .executeWithOptions(_.enableLocalContextPropagation)

  def init(implicit sch: Scheduler): Task[Unit] =
    for {
      mdc <- TaskLocal(Contexter.Context.empty)
      logger = MonixLog.make[Main.type](mdc)
      result <- program(logger)
    } yield result

  def program(
    logger: ContextLogger[Task]
  )(implicit sch: Scheduler): Task[Unit] = {
    val ex = new InvalidParameterException("BOOOOOM")
    for {
      _ <- logger
        .context("a", A(1, "x"))
        .context("o", o)
        .info("Hello Monix")
      _ <- logger.info("Hello MTL", ex)
      _ <- logger.context("x", 123).context("o", o).use {
        logger.context("x", 9).info("Hello2 meow")
      }
    } yield ()
  }

}
