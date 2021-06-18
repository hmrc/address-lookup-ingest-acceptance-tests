import sbt._

object Dependencies {
  private val awsSdkVersion = "2.16.83"
  val test = Seq(
    "software.amazon.awssdk" % "core" % awsSdkVersion,
    "software.amazon.awssdk" % "sts" % awsSdkVersion,
    "software.amazon.awssdk" % "sfn" % awsSdkVersion,
    "software.amazon.awssdk" % "lambda" % awsSdkVersion,
    "me.lamouri" % "jcredstash" % "2.1.1",
    "org.tpolecat" %% "doobie-core" % "0.7.1",
    "org.tpolecat" %% "doobie-postgres" % "0.7.1",
    "org.scalatest" %% "scalatest" % "3.2.0",
    "com.vladsch.flexmark" % "flexmark-all" % "0.35.10"
  )
}
