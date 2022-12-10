import sbt._

object Dependencies {
  val fs2Version    = "3.4.0"
  val doobieVersion = "1.0.0-RC2"

  lazy val commonDeps =
    Seq(
      // util
      "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2",
      "com.typesafe"            % "config"             % "1.4.2",
      // "org.typelevel"          %% "shapeless3-deriving" % "3.2.0",

      // logging
      "org.slf4j"                   % "slf4j-api"       % "2.0.3",
      "ch.qos.logback"              % "logback-classic" % "1.4.4",
      "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5",
    )

  lazy val testDeps =
    Seq(
      "org.scalameta" %% "munit"               % "0.7.29" % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7"  % Test,
    )

  lazy val fs2Deps =
    Seq(
      "org.typelevel" %% "cats-core"            % "2.9.0" withSources () withJavadoc (),
      "org.typelevel" %% "cats-effect"          % "3.4.1" withSources () withJavadoc (),
      "org.typelevel" %% "log4cats-core"        % "2.5.0",
      "org.typelevel" %% "log4cats-slf4j"       % "2.5.0",
      "co.fs2"        %% "fs2-io"               % fs2Version,
      "co.fs2"        %% "fs2-reactive-streams" % fs2Version,
    )

  lazy val serializerDeps =
    Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.17.9",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.17.9" % "provided", // Use "provided" if "compile-internal" is not supported
    )

  lazy val doobieDeps =
    Seq(
      "org.tpolecat"  %% "doobie-core"     % doobieVersion,
      "org.tpolecat"  %% "doobie-hikari"   % doobieVersion,
      "org.tpolecat"  %% "doobie-postgres" % doobieVersion,
      "org.tpolecat"  %% "doobie-h2"       % doobieVersion,
      "org.postgresql" % "postgresql"      % "42.5.0",
    )
}
