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
 * JSON keys, possibilities:
    * always as free-form strings -- simplest solution
    * library of standardized JSON keys
    * JSON keys via extendable type
    * JSON keys via typeclass

## Implementation considerations

 * use Circe for the encoding
 * mimic `slf4j`'s `Logger` API

## Interface

```scala
trait Log[F[_]] {
  // excerpt for `info` logging
  def info[A](message: String): F[Unit]
  def info[A](message: String, name: String, value: A)(implicit e: Encoder[A]): F[Unit]
  def info[A](message: String, ex: Throwable): F[Unit]
  def info[A](message: String, name: String, value: A, ex: Throwable)(implicit e: Encoder[A]): F[Unit]

  // excerpt for augmenting the log context
  def withContext[A, B](name: String, value: A)(inner: F[B])(implicit e: Encoder[A]): F[B]
  def withContext[A, B, C](name1: String, value1: A, name2: String, value2: B)(inner: F[C])(implicit e1: Encoder[A], e2: Encoder[B]): F[C]
}
```

## Usage

```scala
def program(logger: Log[Task])(implicit sch: Scheduler): Task[Unit] = {
  val ex = new InvalidParameterException("BOOOOOM")
  for {
    _ <- logger.withContext("a", A(1, "x")) {
      logger.withContext("o", o) {
        logger.info("Hello Monix")
      }
    }
    _ <- logger.info("Hello MTL", "o", o, ex)
    _ <- logger.withContext("x", 123, "o", o) {
      logger.info("Hello2 meow", "x", 9)
    }
  } yield ()
}
```

### Nested contexts
```scala
_ <- logger.withContext("a", A(1, "x")) {
  logger.withContext("o", o) {
    logger.info("Hello Monix")
  }
}
```
```json
{
  "@timestamp": "2019-11-28T15:59:24.843+01:00",
  "@version": "1",
  "message": "Hello Monix",
  "logger_name": "loggingexperiment.logbackmtl.Main$",
  "thread_name": "scala-execution-context-global-12",
  "level": "INFO",
  "level_value": 20000,
  "a": {
    "x": 1,
    "y": "x"
  },
  "o": {
    "a": {
      "x": 123,
      "y": "Hello"
    },
    "b": true
  },
  "application": "loggingexperiment"
}
```

### Logging exception
```scala
_ <- logger.info("Hello MTL", "o", o, ex)
```
```json
{
  "@timestamp": "2019-11-28T15:59:24.864+01:00",
  "@version": "1",
  "message": "Hello MTL",
  "logger_name": "loggingexperiment.logbackmtl.Main$",
  "thread_name": "scala-execution-context-global-12",
  "level": "INFO",
  "level_value": 20000,
  "stack_trace": "java.security.InvalidParameterException: BOOOOOM\n\tat loggingexperiment.logbackmtl.Main$.program(Main.scala:162)\n\tat loggingexperiment.logbackmtl.Main$.$anonfun$init$1(Main.scala:158)\n\t...",
  "o": {
    "a": {
      "x": 123,
      "y": "Hello"
    },
    "b": true
  },
  "application": "loggingexperiment"
}
```

### Context overriding
```scala
_ <- logger.withContext("x", 123, "o", o) {
  logger.info("Hello2 meow", "x", 9)
}
```
```json
{
  "@timestamp": "2019-11-28T15:59:24.876+01:00",
  "@version": "1",
  "message": "Hello2 meow",
  "logger_name": "loggingexperiment.logbackmtl.Main$",
  "thread_name": "scala-execution-context-global-12",
  "level": "INFO",
  "level_value": 20000,
  "x": 9,
  "o": {
    "a": {
      "x": 123,
      "y": "Hello"
    },
    "b": true
  },
  "application": "loggingexperiment"
}
```
