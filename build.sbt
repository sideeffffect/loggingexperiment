name := "loggingexperiment"

version := "0.1"

scalaVersion := "2.12.10"

lazy val Version = new {
  val slf4j = "1.7.29"
  val logback = "1.2.3"
  val jacksonScala = "2.10.2"
  val logstashLogback = "6.2"
  val monix = "3.1.0"
  val catsMtl = "0.7.0"
  val catsEffect = "2.0.0"
  val meowMtl = "0.4.0"
}

lazy val root = project
  .in(file("."))
  .settings(
    name := "slf4cats",
    publish / skip := true, // doesn't publish ivy XML files, in contrast to "publishArtifact := false"
  )
  .aggregate(
    slf4catsApi,
    slf4catsImpl,
    slf4catsExample,
  )

lazy val slf4catsApi = project
  .in(file("slf4cats-api"))
  .settings(
    name := "slf4cats-api",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Version.slf4j,
      "org.typelevel" %% "cats-effect" % Version.catsEffect,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    ),
  )

lazy val slf4catsImpl = project
  .in(file("slf4cats-impl"))
  .settings(
    name := "slf4cats-impl",
    libraryDependencies ++= Seq(
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % Version.jacksonScala,
      "net.logstash.logback" % "logstash-logback-encoder" % Version.logstashLogback,
      "org.typelevel" %% "cats-mtl-core" % Version.catsMtl,
      "org.typelevel" %% "cats-effect" % Version.catsEffect,
    ),
  )
  .dependsOn(slf4catsApi)

lazy val slf4catsMonix = project
  .in(file("slf4cats-monix"))
  .settings(
    name := "slf4cats-monix",
    libraryDependencies ++= Seq(
      "io.monix" %% "monix" % Version.monix,
      "com.olegpy" %% "meow-mtl-monix" % Version.meowMtl,
    ),
  )
  .dependsOn(slf4catsImpl)

lazy val slf4catsExample = project
  .in(file("slf4cats-example"))
  .settings(
    name := "slf4cats-example",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % Version.logback,
    ),
  )
  .dependsOn(slf4catsMonix)
