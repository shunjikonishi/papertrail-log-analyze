name := "flect-papertrail"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache,
  "com.amazonaws" % "aws-java-sdk" % "1.6.8"
)     

play.Project.playScalaSettings
