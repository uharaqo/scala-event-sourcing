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
    libraryDependencies ++= testDeps,
    run / fork               := true,
    Test / publishArtifact   := false,
    Test / parallelExecution := false,
    sonatypeCredentialHost   := "s01.oss.sonatype.org",
    sonatypeRepository       := "https://s01.oss.sonatype.org/service/local",
  )

val root =
  (project in file("."))
    .settings(publish / skip := true)
    .aggregate(
      eventSourcing,
      eventSourcingPostgres,
      eventSourcingJson,
      eventSourcingProtobuf,
      eventSourcingGrpc,
      example
    )

lazy val eventSourcing =
  (project in file("event-sourcing"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing",
      libraryDependencies ++=
        fs2Deps ++ cacheDeps
    )

lazy val eventSourcingPostgres =
  (project in file("event-sourcing-postgres"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing-postgres",
      libraryDependencies ++= postgresDeps
    )
    .dependsOn(eventSourcing)

lazy val eventSourcingJson =
  (project in file("event-sourcing-json"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing-json",
      libraryDependencies ++= jsonDeps
    )
    .dependsOn(eventSourcing)

lazy val eventSourcingProtobuf =
  (project in file("event-sourcing-protobuf"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing-protobuf",
      libraryDependencies ++= protobufDeps
    )
    .enablePlugins(Fs2Grpc)
    .dependsOn(eventSourcing)

lazy val eventSourcingGrpc =
  (project in file("event-sourcing-grpc"))
    .settings(baseSettings)
    .settings(
      name := "event-sourcing-grpc",
      libraryDependencies ++= protobufDeps ++ grpcDeps,
      scalapbCodeGeneratorOptions ++= Seq(
        CodeGeneratorOption.FlatPackage,
      )
    )
    .enablePlugins(Fs2Grpc)
    .dependsOn(eventSourcingProtobuf)

lazy val exampleProto =
  (project in file("example-proto"))
    .settings(baseSettings)
    .settings(
      name := "example-proto",
      libraryDependencies ++= protobufDeps,
      Compile / PB.targets :=
        Seq(scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "scalapb"),
      publish / skip := true,
    )

lazy val example =
  (project in file("example"))
    .settings(baseSettings)
    .settings(
      name           := "example",
      publish / skip := true,
    )
    .dependsOn(eventSourcing, eventSourcingPostgres, eventSourcingGrpc, exampleProto)
