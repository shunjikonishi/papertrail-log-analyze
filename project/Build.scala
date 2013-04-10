import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {
	
	val appName         = "flect-papertrail"
	val appVersion      = "1.0-SNAPSHOT"
	
	val appDependencies = Seq(
		// Add your project dependencies here,
		jdbc,
		anorm,
		"com.amazonaws" % "aws-java-sdk" % "1.4.1"
	)
	
	val main = play.Project(appName, appVersion, appDependencies).settings(
		// Add your own project settings here      
	)
	
}
