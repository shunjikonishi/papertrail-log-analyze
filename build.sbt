name := "flect-papertrail"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache,
  "org.apache.httpcomponents" % "httpclient" % "4.3.1",
  "com.amazonaws" % "aws-java-sdk" % "1.6.8",
  "com.github.mumoshu" %% "play2-memcached" % "0.4.0",
  "com.google.code.gson" % "gson" % "2.2.4"
)     

play.Project.playScalaSettings

resolvers += "Spy Repository" at "http://files.couchbase.com/maven2"
