import Dependencies.*

val options = Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-encoding",
  "UTF-8",
  "-language:postfixOps",
  "-language:higherKinds",
  // "-Yexplicit-nulls",
)

val baseSettings =
  Seq(
    organization := "io.github.uharaqo",
    homepage     := Some(url("https://github.com/uharaqo/scala-event-sourcing")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("uharaqo", "uharaqo", "uharaqo@users.noreply.github.com", url("https://github.com/uharaqo"))
    ),
    version           := "0.0.10-SNAPSHOT",
    scalaVersion      := "3.2.1",
    scalacOptions     := options,
    scalafmtOnCompile := true,
    libraryDependencies ++= commonDeps ++ testDeps,
    run / fork               := true,
    Test / publishArtifact   := false,
    Test / parallelExecution := false,
    sonatypeCredentialHost   := "s01.oss.sonatype.org",
    sonatypeRepository       := "https://s01.oss.sonatype.org/service/local",
  )

lazy val eventSourcing =
  (project in file("event-sourcing"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing",
      libraryDependencies ++=
        fs2Deps ++ serializerDeps ++ cacheDeps
    )

lazy val eventSourcingDoobie =
  (project in file("event-sourcing-doobie"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing-doobie",
      libraryDependencies ++= doobieDeps
    )
    .dependsOn(eventSourcing)

lazy val eventSourcingGrpc =
  (project in file("event-sourcing-grpc"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing-grpc",
      libraryDependencies ++= grpcDeps
    )
    .enablePlugins(Fs2Grpc)
    .dependsOn(eventSourcing)

lazy val exampleProto =
  (project in file("example-proto"))
    .settings(baseSettings)
    .settings(
      name := "example-proto",
      Compile / PB.targets :=
        Seq(scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "scalapb"),
      libraryDependencies ++= protoDeps,
      publish / skip := true,
    )

lazy val example =
  (project in file("example"))
    .settings(baseSettings)
    .settings(
      name := "example",
      // libraryDependencies ++= serializerDeps ++ cacheDeps,
      publish / skip := true,
    )
    .dependsOn(eventSourcing, eventSourcingDoobie, eventSourcingGrpc, exampleProto)

val root =
  (project in file("."))
    .settings(publish / skip := true)
    .aggregate(eventSourcing, eventSourcingDoobie, eventSourcingGrpc, example)
