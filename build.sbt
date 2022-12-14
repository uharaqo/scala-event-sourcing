import Dependencies._

scalaVersion := "3.2.1"
organization := "com.github.uharaqo"
name         := "scala-event-sourcing"

val options = Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  // "-Yexplicit-nulls",
)

val baseSettings =
  Seq(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= commonDeps ++ testDeps,
    scalaVersion             := "3.2.1",
    scalacOptions            := options,
    Test / parallelExecution := true,
    run / fork               := true,
    scalafmtOnCompile        := true,
  )

lazy val eventSourcingGrpc =
  (project in file("event-sourcing-grpc"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing-grpc",
      libraryDependencies ++= grpcDeps
    )
    .enablePlugins(Fs2Grpc)

lazy val exampleProto =
  (project in file("example-proto"))
    .settings(baseSettings)
    .settings(
      name := "example-proto",
      Compile / PB.targets :=
        Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "scalapb"
        ),
      libraryDependencies ++= protoDeps
    )

lazy val eventSourcing =
  (project in file("event-sourcing"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing",
      libraryDependencies ++=
        fs2Deps ++ serializerDeps ++ doobieDeps ++ cacheDeps
    )

lazy val example =
  (project in file("example"))
    .settings(baseSettings)
    .settings(
      name := "example",
      libraryDependencies ++=
        fs2Deps ++ serializerDeps ++ doobieDeps ++ cacheDeps
    )
    .dependsOn(eventSourcing, eventSourcingGrpc, exampleProto)

val root =
  (project in file("."))
    .aggregate(eventSourcingGrpc, eventSourcing, example)
