import sbt._

object Dependencies {
  private val awsSdkVersion = "2.16.83"
  val test = Seq(
    "software.amazon.awssdk" % "core" % awsSdkVersion,
    "software.amazon.awssdk" % "sts" % awsSdkVersion,
    "software.amazon.awssdk" % "sfn" % awsSdkVersion,
    "software.amazon.awssdk" % "lambda" % awsSdkVersion,
    "me.lamouri" % "jcredstash" % "2.1.1",
    "org.postgresql" % "postgresql" % "42.2.22",
    "org.scalatest" %% "scalatest" % "3.2.0" % Test,
    "com.vladsch.flexmark" % "flexmark-all" % "0.35.10" % Test
  )
}
