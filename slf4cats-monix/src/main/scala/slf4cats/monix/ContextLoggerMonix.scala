package slf4cats.monix

import com.olegpy.meow.monix._
import monix.eval.{Task, TaskLocal}
import slf4cats.api.ContextLogger
import slf4cats.impl.ContextLogger

import scala.reflect.ClassTag

object ContextLoggerMonix {

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
