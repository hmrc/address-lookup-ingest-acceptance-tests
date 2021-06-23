/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.specs

import cats.effect.{ContextShift, IO}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicSessionCredentials}
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import me.lamouri.JCredStash
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import software.amazon.awssdk.auth.credentials.{AwsSessionCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.{DescribeExecutionRequest, ExecutionStatus, StartExecutionRequest}
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.{AssumeRoleRequest, Credentials}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.Random

class IngestSpec extends AsyncWordSpec with Matchers {
  private val credstashTableName = "credential-store"
  private val context: java.util.Map[String, String] =
    Map("role" -> "address_lookup_file_download").asJava

  val assumeRoleCreds: Option[Credentials] = Option(System.getenv("ROLE_ARN"))
    .map(roleArn => {
      val stsClient: StsClient = StsClient.create()

      val assumeRoleRequest: AssumeRoleRequest = AssumeRoleRequest.builder()
        .roleArn(roleArn)
        .roleSessionName("address-lookup-ingest-acceptance-tests")
        .build()

      stsClient.assumeRole(assumeRoleRequest).credentials()
    })

  val credStash: JCredStash = assumeRoleCreds match {
    case Some(creds) => createCredStashClientWithCreds(creds)
    case _ => new JCredStash()
  }

  val lambdaClient: LambdaClient = assumeRoleCreds match {
    case Some(creds) => createLambdaClientWithCreds(creds)
    case _ => LambdaClient.create()
  }

  val sfnClient: SfnClient = assumeRoleCreds match {
    case Some(creds) => createStepFunctionClientWithCreds(creds)
    case _ => SfnClient.create()
  }

  private def retrieveCredentials(credential: String) = {
    credStash.getSecret(credstashTableName, credential, context)
  }

  "Address lookup data ingest" when {
    val testDataLambdaName = "addressLookupCopyTestDataLambdaFunction"
    val stepFunctionName = "addressLookupIngestStateMachine"
    val testEpoch = (Random.nextInt(9999) + 500).toString //Make sure that we dont have an actual epoch number
    val xtor: Aux[IO, Unit] = transactor

    "the step function has not yet run" should {
      val copyTestDataRun =
        lambdaClient.invoke(InvokeRequest.builder().functionName(testDataLambdaName).payload(SdkBytes.fromUtf8String(testEpoch)).build())
      val copyTestDataRunStatus = copyTestDataRun.statusCode()
      copyTestDataRunStatus shouldBe 200

      "pass" in {
        true shouldBe true
      }
      "not contain the expected schema" in {
        sql"""SELECT viewname, definition
             |FROM pg_views
             |WHERE viewname = 'address_lookup'
             |AND schemaname = 'public'""".stripMargin
          .query[(String, String)]
          .option
          .transact(xtor).unsafeToFuture()
          .collect {
            case Some(result) =>
              result._1 shouldBe "address_lookup"
              result._2 should not include (s"FROM ab${testEpoch}_")
          }
      }
    }

    "step function execution has completed" should {
      val stepFunctionArn =
        sfnClient.listStateMachines().stateMachines().asScala.find(_.name() == stepFunctionName)
          .map(_.stateMachineArn()).getOrElse("STEP_FUNCTION_NOT_FOUND")

      val startExecutionRequest =
        StartExecutionRequest
          .builder()
          .stateMachineArn(stepFunctionArn)
          .input(s"$testEpoch")
          .build()
      val executeSfnResponse = sfnClient.startExecution(startExecutionRequest)
      val executionArn = executeSfnResponse.executionArn()

      val executionRequest = DescribeExecutionRequest.builder().executionArn(executionArn).build()
      var describeStatusResponse = sfnClient.describeExecution(executionRequest)
      while (describeStatusResponse.status() != ExecutionStatus.SUCCEEDED || describeStatusResponse.status() != ExecutionStatus.FAILED || describeStatusResponse.status() != ExecutionStatus.TIMED_OUT) {
        Thread.sleep(60000)
        describeStatusResponse = sfnClient.describeExecution(executionRequest)
      }

      "complete succesfully" in {
        describeStatusResponse.status() shouldBe ExecutionStatus.SUCCEEDED
      }

      "switch the public view to the new schema" in {
        sql"""SELECT viewname, definition
             | FROM pg_views
             | WHERE viewname = 'address_lookup'
             | AND schemaname = 'public'""".stripMargin
          .query[(String, String)]
          .unique
          .transact(xtor).unsafeToFuture()
          .map {
            case result =>
              result._1 shouldBe "address_lookup"
              result._2 should include(s"FROM ab${testEpoch}_")
          }
      }

      "return data as expected" in {
        sql"""SELECT postcode
             | FROM address_lookup
             | WHERE postcode LIKE 'BT12 %'""".stripMargin
          .query[String]
          .to[List]
          .transact(xtor).unsafeToFuture()
          .map(result => result should not be empty)
      }
    }
  }

  private def transactor = {
    val host: String = retrieveCredentials("address_lookup_rds_host")
    val port: String = "5432"
    val database: String = retrieveCredentials("address_lookup_rds_database")
    val admin: String = retrieveCredentials("address_lookup_rds_readonly_user")
    val adminPassword: String = retrieveCredentials("address_lookup_rds_readonly_password")

    implicit val cs: ContextShift[IO] =
      IO.contextShift(implicitly[ExecutionContext])

    val dbUrl = s"jdbc:postgresql://$host:$port/$database?searchpath=public"
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      dbUrl,
      admin,
      adminPassword
    )
  }

  private def createCredStashClientWithCreds(creds: Credentials) = {
    val provider = new AWSStaticCredentialsProvider(
      new BasicSessionCredentials(creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken()))
    new JCredStash(provider)
  }

  private def createLambdaClientWithCreds(creds: Credentials) = {
    val session = AwsSessionCredentials.create(creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken())

    LambdaClient.builder()
      .credentialsProvider(StaticCredentialsProvider.create(session))
      .build()
  }

  private def createStepFunctionClientWithCreds(creds: Credentials) = {
    val session = AwsSessionCredentials.create(creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken())

    SfnClient.builder()
      .credentialsProvider(StaticCredentialsProvider.create(session))
      .build()
  }
}
