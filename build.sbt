name := "flect-papertrail"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache,
  "org.apache.httpcomponents" % "httpclient" % "4.3.1",
  "com.amazonaws" % "aws-java-sdk" % "1.6.8"
)     

play.Project.playScalaSettings
