import sbt.*

object Dependencies {
  val fs2Version        = "3.4.0"
  val doobieVersion     = "1.0.0-RC2"
  val jsoniterVersion   = "2.19.1"
  val scalaPbVersion    = "0.11.12"
  val scalaCacheVersion = "1.0.0-M6"

  lazy val fs2Deps =
    Seq(
      ("org.typelevel" %% "cats-core"      % "2.9.0").withSources().withJavadoc(),
      ("org.typelevel" %% "cats-effect"    % "3.4.1").withSources().withJavadoc(),
      "org.typelevel"  %% "log4cats-core"  % "2.5.0",
      "org.typelevel"  %% "log4cats-slf4j" % "2.5.0",
      "co.fs2"         %% "fs2-io"         % fs2Version,
    )

  lazy val jsonDeps =
    Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % "provided", // Use "provided" if "compile-internal" is not supported
    )

  lazy val cacheDeps =
    Seq(
      "com.github.cb372" %% "scalacache-core"     % scalaCacheVersion,
      "com.github.cb372" %% "scalacache-caffeine" % scalaCacheVersion,
    )

  lazy val postgresDeps =
    Seq(
      "org.tpolecat"  %% "doobie-core"     % doobieVersion,
      "org.tpolecat"  %% "doobie-hikari"   % doobieVersion,
      "org.tpolecat"  %% "doobie-h2"       % doobieVersion,
      "org.tpolecat"  %% "doobie-postgres" % doobieVersion,
      "org.postgresql" % "postgresql"      % "42.5.1",
    )

  lazy val protobufDeps =
    Seq(
      "com.thesamet.scalapb" %% "compilerplugin"  % scalaPbVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalaPbVersion % "protobuf",
  )

  lazy val protobufJsonDep = "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.0"

  lazy val grpcDeps =
    Seq(
      "io.grpc" % "grpc-netty-shaded" % "1.51.0",
    )

  lazy val testDeps =
    Seq(
      "org.scalameta" %% "munit"               % "0.7.29" % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7"  % Test,
      "org.slf4j"      % "slf4j-api"           % "2.0.5"  % Test,
      "ch.qos.logback" % "logback-classic"     % "1.4.5"  % Test,
    )
}
