/*
 * Copyright 2023 HM Revenue & Customs
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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.auth.credentials.{AwsSessionCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.{DescribeExecutionRequest, ExecutionStatus, StartExecutionRequest}
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.{AssumeRoleRequest, Credentials}

import java.nio.charset.Charset
import scala.collection.JavaConverters._
import scala.util.Random

class IngestSpec extends AnyWordSpec with Matchers {
  val copyTestDataLambdaName  = "cipAddressLookupCopyTestDataLambdaFunction"
  val checkTestDataLambdaName = "cipAddressLookupCheckTestDataLambdaFunction"
  val stepFunctionName        = "cipAddressLookupIngestStateMachine"
  val testEpoch               = (Random.nextInt(9999) + 500).toString //Make sure that we dont have an actual epoch number

  private val assumeRoleCreds: Option[Credentials] = Option(System.getenv("ROLE_ARN"))
    .map { roleArn =>
      val stsClient: StsClient = StsClient.builder().region(Region.EU_WEST_2).build()

      val assumeRoleRequest: AssumeRoleRequest =
        AssumeRoleRequest
          .builder()
          .roleArn(roleArn)
          .roleSessionName("address-lookup-ingest-acceptance-tests")
          .build()

      stsClient.assumeRole(assumeRoleRequest).credentials()
    }

  private def lambdaClient: LambdaClient = assumeRoleCreds match {
    case Some(creds) => createLambdaClientWithCreds(creds)
    case _           => LambdaClient.create()
  }

  private def sfnClient: SfnClient = assumeRoleCreds match {
    case Some(creds) => createStepFunctionClientWithCreds(creds)
    case _           => SfnClient.create()
  }

  private def createLambdaClientWithCreds(creds: Credentials) = {
    val session = AwsSessionCredentials.create(creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken())

    LambdaClient
      .builder()
      .credentialsProvider(StaticCredentialsProvider.create(session))
      .build()
  }

  private def createStepFunctionClientWithCreds(creds: Credentials) = {
    val session = AwsSessionCredentials.create(creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken())

    SfnClient
      .builder()
      .credentialsProvider(StaticCredentialsProvider.create(session))
      .build()
  }

  "Address lookup data ingest" when {
    "the address_lookup view ddl" should {
      "not contain the expected schema" in {
        val checkTestDataRun = lambdaClient.invoke(
          InvokeRequest
            .builder()
            .functionName(checkTestDataLambdaName)
            .payload(SdkBytes.fromUtf8String(testEpoch))
            .build()
        )
        val response         = checkTestDataRun.payload().asString(Charset.forName("utf-8"))

        checkTestDataRun.statusCode() shouldBe 200
        response                        should include(s""""schemaName":"NOT_FOUND"""")
      }
    }

    "the step function has not yet run" should {
      "copy test data should run successfully" in {
        val copyTestDataRun       =
          lambdaClient.invoke(
            InvokeRequest
              .builder()
              .functionName(copyTestDataLambdaName)
              .payload(SdkBytes.fromUtf8String(testEpoch))
              .build()
          )
        val copyTestDataRunStatus = copyTestDataRun.statusCode()

        copyTestDataRunStatus shouldBe 200
      }
    }

    "step function execution has completed" should {
      "complete succesfully" in {
        val stepFunctionArn =
          sfnClient
            .listStateMachines()
            .stateMachines()
            .asScala
            .find(_.name() == stepFunctionName)
            .map(_.stateMachineArn())
            .getOrElse("STEP_FUNCTION_NOT_FOUND")

        val startExecutionRequest =
          StartExecutionRequest
            .builder()
            .stateMachineArn(stepFunctionArn)
            .input(s"""{"epoch": "$testEpoch"}""")
            .build()
        val executeSfnResponse    = sfnClient.startExecution(startExecutionRequest)
        val executionArn          = executeSfnResponse.executionArn()

        val executionRequest       = DescribeExecutionRequest.builder().executionArn(executionArn).build()
        var describeStatusResponse = sfnClient.describeExecution(executionRequest)
        while (
          describeStatusResponse.status() != ExecutionStatus.SUCCEEDED && describeStatusResponse
            .status() != ExecutionStatus.FAILED && describeStatusResponse.status() != ExecutionStatus.TIMED_OUT
        ) {
          Thread.sleep(60000)
          describeStatusResponse = sfnClient.describeExecution(executionRequest)
        }

        describeStatusResponse.status() shouldBe ExecutionStatus.SUCCEEDED
      }
    }

    "checking for data" should {
      "return data as expected" in {
        val checkTestDataRun = lambdaClient.invoke(
          InvokeRequest
            .builder()
            .functionName(checkTestDataLambdaName)
            .payload(SdkBytes.fromUtf8String(testEpoch))
            .build()
        )
        val response         = checkTestDataRun.payload().asString(Charset.forName("utf-8"))

        checkTestDataRun.statusCode() shouldBe 200
        response                        should include(s""""schemaName":"ab$testEpoch""")
      }
    }
  }
}
