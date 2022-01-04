val awsSdkVersion = "2.16.83"

lazy val testSuite = (project in file("."))
  .enablePlugins(SbtAutoBuildPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    name := "address-lookup-ingest-acceptance-tests",
    version := "0.1.0",
    scalaVersion := "2.12.12",
    scalacOptions ++= Seq("-feature"),
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "core"            % awsSdkVersion,
      "software.amazon.awssdk" % "sts"             % awsSdkVersion,
      "software.amazon.awssdk" % "sfn"             % awsSdkVersion,
      "software.amazon.awssdk" % "lambda"          % awsSdkVersion,
      "org.tpolecat"          %% "doobie-core"     % "0.7.1",
      "org.tpolecat"          %% "doobie-postgres" % "0.7.1",
      "org.scalatest"         %% "scalatest"       % "3.2.0",
      "com.vladsch.flexmark"   % "flexmark-all"    % "0.35.10"
    )
  )
