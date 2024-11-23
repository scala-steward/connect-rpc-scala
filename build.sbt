ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.4"

lazy val Versions = new {
  val grpc   = "1.68.1"
  val http4s = "0.23.29"
}

lazy val core = project
  .settings(
    name := "connect-rpc-scala",

    Test / PB.targets := Seq(
      scalapb.gen() -> (Test / sourceManaged).value
    ),

    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.1",
      "io.grpc" % "grpc-core" % Versions.grpc,
      "io.grpc" % "grpc-protobuf" % Versions.grpc,
      "io.grpc" % "grpc-inprocess" % Versions.grpc,

      "org.http4s" %% "http4s-dsl" % Versions.http4s,
      "org.http4s" %% "http4s-client" % Versions.http4s % Test,

      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    ),
  )
