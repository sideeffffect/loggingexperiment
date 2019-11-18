name := "loggingexperiment"

version := "0.1"

scalaVersion := "2.12.10"

lazy val Version = new {
  val circe = "0.11.1"
  val slf4j = "1.7.29"
  val logback = "1.2.3"
  val logstashLogback = "6.2"
  val zio = "1.0.0-RC16"
  val monix = "3.1.0"
  val izumi = "0.9.12"
  val gson = "2.8.6"
  val jackson = "2.9.8"
}

lazy val root = project
  .in(file("."))
  .settings(
    name := "loggingexperiment",
    publish / skip := true, // doesn't publish ivy XML files, in contrast to "publishArtifact := false"
  )
  .aggregate(
    logbackZio,
    logbackMonixGson,
    logbackMonixJackson,
    logstageMonix,
  )

lazy val logbackZio = project
  .in(file("logback-zio"))
  .settings(
    name := "logback-zio",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Version.slf4j,
      "ch.qos.logback" % "logback-classic" % Version.logback,
      "net.logstash.logback" % "logstash-logback-encoder" % Version.logstashLogback,
      "io.circe" %% "circe-core" % Version.circe,
      "io.circe" %% "circe-generic" % Version.circe,
      "dev.zio" %% "zio" % Version.zio,
    )
  )

lazy val logbackMonixGson = project
  .in(file("logback-monix-gson"))
  .settings(
    name := "logback-monix-gson",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Version.slf4j,
      "ch.qos.logback" % "logback-classic" % Version.logback,
      "net.logstash.logback" % "logstash-logback-encoder" % Version.logstashLogback,
      "com.google.code.gson" % "gson" % Version.gson,
      "io.monix" %% "monix" %  Version.monix,
    )
  )

lazy val logbackMonixJackson = project
  .in(file("logback-monix-jackson"))
  .settings(
    name := "logback-monix-jackson",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Version.slf4j,
      "ch.qos.logback" % "logback-classic" % Version.logback,
      "net.logstash.logback" % "logstash-logback-encoder" % Version.logstashLogback,
      "com.fasterxml.jackson.core" % "jackson-databind" % Version.jackson,
      "io.monix" %% "monix" %  Version.monix,
    )
  )

lazy val logstageMonix = project
  .in(file("logstage-monix"))
  .settings(
    name := "logstage-monix",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Version.slf4j,
      "ch.qos.logback" % "logback-classic" % Version.logback,
      "net.logstash.logback" % "logstash-logback-encoder" % Version.logstashLogback,
      "io.circe" %% "circe-core" % Version.circe,
      "io.circe" %% "circe-generic" % Version.circe,
      "io.monix" %% "monix" %  Version.monix,
      "io.7mind.izumi" %% "logstage-core" % Version.izumi,
      "io.7mind.izumi" %% "logstage-rendering-circe" % Version.izumi,
      "io.7mind.izumi" %% "logstage-sink-slf4j" % Version.izumi,
    )
  )
