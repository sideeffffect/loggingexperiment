package loggingexperiment.logstagemonix

import cats.effect._
import io.circe.generic.auto._
import izumi.logstage.api.rendering.json.LogstageCirceRenderingPolicy
import izumi.logstage.sink.slf4j.LogSinkLegacySlf4jImpl
import logstage._
import logstage.circe._
import monix.eval._
import monix.execution.Scheduler

final case class A(x: Int, y: String)
final case class B(a: A, b: Boolean)

object Main extends TaskApp {

  val sink = new LogSinkLegacySlf4jImpl(new LogstageCirceRenderingPolicy(true))
  val jsonSink: ConsoleSink = ConsoleSink.json(prettyPrint = true)
  val logger = IzLogger(Trace, List(sink, jsonSink))
  val loggerTask: LogIO[Task] = LogIO.fromLogger[Task](logger)

  val o = B(A(123, "Hel\nlo"), b = true)

  override def run(args: List[String]): Task[ExitCode] =
    init(scheduler)
      .map(_ => ExitCode.Success)
      .executeWithOptions(_.enableLocalContextPropagation)

  def init(implicit sch: Scheduler): Task[Unit] =
    for {
      result <- program(loggerTask)
    } yield result

  def program(logger: LogIO[Task])(implicit sch: Scheduler): Task[Unit] = {
    val logger_ = logger("yyy" -> A(567, "YYYYYYYYYYYY"))
    val justAList = List[Any](10, "green", "bottles")
    for {
      _ <- logger_.info(s"Hello $o")
      _ <- logger.info(s"Hello $o")
      _ <- logger.info(s"Hello2 ${123} and $o")
      _ <- logger.info(s"Argument: $justAList")
    } yield ()
  }

}
