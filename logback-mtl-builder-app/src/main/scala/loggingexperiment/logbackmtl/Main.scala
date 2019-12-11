package loggingexperiment.logbackmtl

import java.security.InvalidParameterException

import cats.effect._
import com.olegpy.meow.monix._
import io.circe.generic.auto._
import monix.eval._
import slf4cats._

import scala.reflect.ClassTag

object MonixLog {
  def make(logger: org.slf4j.Logger)(
    taskLocalContext: TaskLocal[ContextManager.Context[Task]]
  ): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.make(logger)
    }
  }

  def make(name: String)(
    taskLocalContext: TaskLocal[ContextManager.Context[Task]]
  ): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.make(name)
    }
  }

  def make[T](
    taskLocalContext: TaskLocal[ContextManager.Context[Task]]
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
    init
      .map(_ => ExitCode.Success)
      .executeWithOptions(_.enableLocalContextPropagation)

  def init: Task[Unit] =
    for {
      mdc <- TaskLocal(ContextManager.Context.empty[Task])
      logger = MonixLog.make[Main.type](mdc)
      result <- program(logger)
    } yield result

  def program(logger: ContextLogger[Task]): Task[Unit] = {
    val ex = new InvalidParameterException("BOOOOOM")
    for {
      _ <- logger
        .withArg("a", A(1, "x"))
        .withArg("o", o)
        .info("Hello Monix")
      _ <- logger.info("Hello MTL", ex)
      _ <- logger.withArg("x", 123).withArg("o", o).use {
        logger.withArg("x", 9).info("Hello2 meow")
      }
    } yield ()
  }

}
