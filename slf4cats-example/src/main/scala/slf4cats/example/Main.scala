package slf4cats.example

import java.security.InvalidParameterException

import cats.effect._
import com.olegpy.meow.monix._
import monix.eval._
import slf4cats.api._
import slf4cats.impl._

import scala.reflect.ClassTag

object MonixLog {
  def make(logger: org.slf4j.Logger)(
      taskLocalContext: TaskLocal[ContextLogger.Context[Task]],
  ): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.fromLogger(logger)
    }
  }

  def make(name: String)(
      taskLocalContext: TaskLocal[ContextLogger.Context[Task]],
  ): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.fromName(name)
    }
  }

  def make[T](
      taskLocalContext: TaskLocal[ContextLogger.Context[Task]],
  )(implicit classTag: ClassTag[T]): ContextLogger[Task] = {
    taskLocalContext.runLocal { implicit ev =>
      ContextLogger.fromClass()
    }
  }
}

object Main extends TaskApp {

  final case class A(x: Int, y: String, bytes: Array[Byte])

  final case class B(a: A, b: List[Boolean], c: Either[String, Int])

  private val o =
    B(A(123, "Hello", Array(1, 2, 3)), List(false, true), Right(456))

  override def run(args: List[String]): Task[ExitCode] =
    init
      .map(_ => ExitCode.Success)
      .executeWithOptions(_.enableLocalContextPropagation)

  def init: Task[Unit] =
    for {
      mdc <- TaskLocal(ContextLogger.Context.empty[Task])
      logger = MonixLog.make[Main.type](mdc)
      result <- program(logger)
    } yield result

  def program(logger: ContextLogger[Task]): Task[Unit] = {
    import slf4cats.encoders.jackson._
    val ex = new InvalidParameterException("BOOOOOM")
    for {
      _ <- logger
        .withArg("a", A(1, "x", Array(127)))
        .withArg("o", o)
        .info("Hello Monix")
      _ <- logger.warn("Hello MTL", ex)
      // test shadowing of arg "x"
      _ <- logger.withArg("x", 123).withArg("o", o).use {
        logger.withArg("x", List(1, 2, 3)).info("Hello2 meow")
      }
      // test context passing on child fibers
      _ <- logger.withArg("o", o).use {
        logger.withArg("x", List("x")).info("Hello in child fiber").start.flatMap { _ =>
          logger.withArg("y", List("y")).info("Hello back in parent fiber")
        }
      }
      // test circe encoder
      _ <- logCirce(logger)
    } yield ()
  }

  private def logCirce(logger: ContextLogger[Task]): Task[Unit] = {
    import slf4cats.encoders.circe._
    import io.circe._
    import io.circe.generic.semiauto._
    implicit val byteArrayEncoder: Encoder[Array[Byte]] = (_: Array[Byte]) => Json.Null
    implicit val aDecoder: Encoder[A] = deriveEncoder
    implicitly[Encoder[Array[Byte]]] // to avoid incorrect error that byteArrayEncoder is never used
    logger.withArg("circe", A(1, "b", Array(1, 2))).info("Logging with circe-encoded class")
  }

}
