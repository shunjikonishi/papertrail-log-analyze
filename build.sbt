name := "flect-papertrail"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  cache,
  "org.apache.httpcomponents" % "httpclient" % "4.5.2",
  "com.amazonaws" % "aws-java-sdk" % "1.10.73",
  "com.google.code.gson" % "gson" % "2.6.2"
)     

routesGenerator := InjectedRoutesGenerator

sources in (Compile, doc) := Seq.empty

