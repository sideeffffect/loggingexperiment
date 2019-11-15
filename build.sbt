name := "loggingexperiment"

version := "0.1"

scalaVersion := "2.12.10"

val circeVersion = "0.11.1"

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "1.7.29",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "net.logstash.logback" % "logstash-logback-encoder" % "6.2",
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
//  "org.typelevel" %% "cats-effect" % "2.0.0",
  "dev.zio" %% "zio" % "1.0.0-RC16",
)
