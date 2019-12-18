name := "loggingexperiment"

version := "0.1"

scalaVersion := "2.12.10"

lazy val Version = new {
  val slf4j = "1.7.29"
  val logback = "1.2.3"
  val logstashLogback = "6.2"
  val monix = "3.1.0"
  val catsMtl = "0.7.0"
  val catsEffect = "2.0.0"
  val meowMtl = "0.4.0"
}

lazy val root = project
  .in(file("."))
  .settings(
    name := "loggingexperiment",
    publish / skip := true, // doesn't publish ivy XML files, in contrast to "publishArtifact := false"
  )
  .aggregate(
    logbackMtlBuilder,
    logbackMtlBuilderApp,
  )

lazy val logbackMtlBuilder = project
  .in(file("logback-mtl-builder"))
  .settings(
    name := "logback-mtl-builder",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % Version.slf4j,
      "ch.qos.logback" % "logback-classic" % Version.logback,
      "net.logstash.logback" % "logstash-logback-encoder" % Version.logstashLogback,
      "org.typelevel" %% "cats-mtl-core" % Version.catsMtl,
      "org.typelevel" %% "cats-effect" % Version.catsEffect,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    )
  )

lazy val logbackMtlBuilderApp = project
  .in(file("logback-mtl-builder-app"))
  .settings(
    name := "logback-mtl-builder-app",
    libraryDependencies ++= Seq(
      "io.monix" %% "monix" %  Version.monix,
      "com.olegpy" %% "meow-mtl-monix" % Version.meowMtl,
    )
  )
  .dependsOn(logbackMtlBuilder)
