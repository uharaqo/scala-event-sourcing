import Version._

scalaVersion := "3.2.1"
organization := "com.github.uharaqo"
name         := "scala-event-sourcing"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
)

val baseSettings =
  Seq(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= commonDeps ++ testDeps,
    scalaVersion             := "3.2.1",
    Test / parallelExecution := true,
    run / fork               := true,
  )

lazy val eventSourcing =
  (project in file("event-sourcing"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing",
      libraryDependencies ++=
        fs2Deps ++ serializerDeps ++ doobieDeps
    )

val root =
  (project in file("."))
    .aggregate(eventSourcing)
