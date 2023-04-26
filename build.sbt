val awsSdkVersion = "2.16.83"

lazy val testSuite = (project in file("."))
  .enablePlugins(SbtAutoBuildPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    name := "address-lookup-ingest-acceptance-tests",
    version := "0.1.0",
    scalaVersion := "2.13.10",
    scalacOptions ++= Seq("-feature"),
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "core"            % awsSdkVersion,
      "software.amazon.awssdk" % "sts"             % awsSdkVersion,
      "software.amazon.awssdk" % "sfn"             % awsSdkVersion,
      "software.amazon.awssdk" % "lambda"          % awsSdkVersion,
      "org.tpolecat"          %% "doobie-core"     % "0.13.4",
      "org.tpolecat"          %% "doobie-postgres" % "0.13.4",
      "org.scalatest"         %% "scalatest"       % "3.2.12",
      "com.vladsch.flexmark"   % "flexmark-all"    % "0.62.2"
    )
  )
