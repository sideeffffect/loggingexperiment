package slf4cats.example

import java.security.InvalidParameterException

import cats.effect._
import monix.eval._
import slf4cats.api._
import slf4cats.impl._
import slf4cats.monix.ContextLoggerMonix

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
      logger = ContextLoggerMonix.make[Main.type](mdc)
      result <- program(logger)
    } yield result

  def program(logger: ContextLogger[Task]): Task[Unit] = {
    val ex = new InvalidParameterException("BOOOOOM")
    for {
      _ <- logger
        .withArg("a", A(1, "x", Array(127)))
        .withArg("o", o)
        .info("Hello Monix")
      _ <- logger.warn("Hello MTL", ex)
      _ <- logger.withArg("x", 123).withArg("o", o).use {
        logger.withArg("x", List(1, 2, 3)).info("Hello2 meow")
      }
      _ <- logger
        .withArg(
          "xxxx",
          1,
          Some((_: Any) => throw new RuntimeException("asdf")),
        )
        .info("yyyy")
    } yield ()
  }

}
