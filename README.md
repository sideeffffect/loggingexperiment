# Structured logging framework 4 Cats

This repo contains experiments with possible implementations of structured logging with `cats-effect`.

## Goals

 * log commands are side-effecting programs, based on `cats-effect`
 * objects provided to log commands should appear as JSON in logs
   * they're searchable in Kibana
   * we won't duplicate the objects in message itself to reduce size
 * logs will contain appropriate context
   * the context can be programmatically augmented
   * works in a stack-like manner, including shadowing
   * works well with `cats-effect` and related libraries (is not bound to `ThreadLocal`/fat JVM thread like slf4j's MDC)
 * other useful metadata in logs
   * timestamp
   * file name
   * line number
   * loglevel as both string and number
   * ... to be specified
 * JSON keys will be just strings, at lest for the beginning

## Features

 * thin wrapper over `slf4j`
    * each logging statement is inlined on the same line using macros
    * rest of the logging pipeline works as expected
 * JSON logging
    * uses `logstash-logback-encoder` for JSON log formatting
    * uses Jackson for the encoding of log parameters by default (imported by Logback Logstash encoder anyway; can be overridden if necessary)
 * CPU expensive or side-effectful actions can be passed to logs
    * will be memoized == computed only once or never at all, if log level too low

## Interface

```scala
trait LoggingContext[F[_]] {
  type Self <: LoggingContext[F]
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
  // excerpt for `info` logging
  def info: LoggerInfo[F]
  //...
}

trait ContextLogger[F[_]] extends LoggingContext[F] with Logger[F] {
  type Self <: ContextLogger[F]
}
class LoggerInfo[F[_]]() {
  def apply(message: String): F[Unit] = macro ???
  def apply(message: String, throwable: Throwable): F[Unit] = macro ???
}
```

## Usage

```scala
def program(logger: ContextLogger[Task]): Task[Unit] = {
  val ex = new InvalidParameterException("BOOOOOM")
  for {
    _ <- logger
      .withArg("a", A(1, "x", Array[Byte](127)))
      .withArg("o", o)
      .info("Hello Monix")
    _ <- logger.warn("Hello MTL", ex)
    _ <- logger.withArg("x", 123).withArg("o", o).use {
      logger.withArg("x", 9).info("Hello2 meow")
    }
  } yield ()
}
```

### Multiple contexts
```scala
_ <- logger
  .withArg("a", A(1, "x", Array[Byte](127)))
  .withArg("o", o)
  .info("Hello Monix")
```
```json
{
  "@timestamp": "2020-01-08T02:31:16.503+01:00",
  "@version": "1",
  "message": "Hello Monix",
  "logger_name": "slf4cats.example.Main$",
  "thread_name": "scala-execution-context-global-13",
  "level": "INFO",
  "level_value": 20000,
  "a": {
    "x": 1,
    "y": "x",
    "bytes": "fw=="
  },
  "o": {
    "a": {
      "x": 123,
      "y": "Hello",
      "bytes": "AQID"
    },
    "b": [
      false,
      true
    ],
    "c": {
      "r": 456
    }
  },
  "application": "loggingexperiment",
  "caller_class_name": "slf4cats.example.Main$",
  "caller_method_name": "$anonfun$program$5",
  "caller_file_name": "Main.scala",
  "caller_line_number": 66
}
```

### Logging exception
```scala
_ <- logger.warn("Hello MTL", ex)
```
```json
{
  "@timestamp": "2020-01-08T02:31:16.520+01:00",
  "@version": "1",
  "message": "Hello MTL",
  "logger_name": "slf4cats.example.Main$",
  "thread_name": "scala-execution-context-global-13",
  "level": "WARN",
  "level_value": 30000,
  "stack_trace": "java.security.InvalidParameterException: BOOOOOM\n\tat slf4cats.example.Main$.program(Main.scala:61)\n...",
  "application": "loggingexperiment",
  "caller_class_name": "slf4cats.example.Main$",
  "caller_method_name": "$anonfun$program$9",
  "caller_file_name": "Main.scala",
  "caller_line_number": 67
}
```

### Context overriding
```scala
_ <- logger.withArg("x", 123).withArg("o", o).use {
  logger.withArg("x", 9).info("Hello2 meow")
}
```
```json
{
  "@timestamp": "2020-01-08T02:31:16.539+01:00",
  "@version": "1",
  "message": "Hello2 meow",
  "logger_name": "slf4cats.example.Main$",
  "thread_name": "scala-execution-context-global-13",
  "level": "INFO",
  "level_value": 20000,
  "x": [
    1,
    2,
    3
  ],
  "o": {
    "a": {
      "x": 123,
      "y": "Hello",
      "bytes": "AQID"
    },
    "b": [
      false,
      true
    ],
    "c": {
      "r": 456
    }
  },
  "application": "loggingexperiment",
  "caller_class_name": "slf4cats.example.Main$",
  "caller_method_name": "$anonfun$program$16",
  "caller_file_name": "Main.scala",
  "caller_line_number": 69
}
```
