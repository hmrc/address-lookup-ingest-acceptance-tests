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

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicSessionCredentials}
import me.lamouri.JCredStash
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
  val copyTestDataLambdaName = "addressLookupCopyTestDataLambdaFunction"
  val checkTestDataLambdaName = "addressLookupCheckTestDataLambdaFunction"
  val stepFunctionName = "addressLookupIngestStateMachine"
  val testEpoch = (Random.nextInt(9999) + 500).toString //Make sure that we dont have an actual epoch number

  private val credstashTableName = "credential-store"
  private val context: java.util.Map[String, String] = Map("role" -> "address_lookup_file_download").asJava

  private val assumeRoleCreds: Option[Credentials] = Option(System.getenv("ROLE_ARN"))
      .map { roleArn =>
        val stsClient: StsClient = StsClient.builder().region(Region.EU_WEST_2).build()

        val assumeRoleRequest: AssumeRoleRequest =
          AssumeRoleRequest.builder()
                           .roleArn(roleArn)
                           .roleSessionName("address-lookup-ingest-acceptance-tests")
                           .build()

        stsClient.assumeRole(assumeRoleRequest).credentials()
      }

  private val credStash: JCredStash = assumeRoleCreds match {
    case Some(creds) => createCredStashClientWithCreds(creds)
    case _           => new JCredStash()
  }

  private val lambdaClient: LambdaClient = assumeRoleCreds match {
    case Some(creds) => createLambdaClientWithCreds(creds)
    case _           => LambdaClient.create()
  }

  private val sfnClient: SfnClient = assumeRoleCreds match {
    case Some(creds) => createStepFunctionClientWithCreds(creds)
    case _           => SfnClient.create()
  }

  private def retrieveCredentials(credential: String) = {
    credStash.getSecret(credstashTableName, credential, context)
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

  println(s">>> testEpoch: $testEpoch")

  "Address lookup data ingest" when {
    "the address_lookup view ddl" should {
      "not contain the expected schema" in {
        val checkTestDataRun = lambdaClient.invoke(InvokeRequest.builder().functionName(checkTestDataLambdaName).payload(SdkBytes.fromUtf8String(testEpoch)).build())
        checkTestDataRun.statusCode() shouldBe 200
        val response = checkTestDataRun.payload().asString(Charset.forName("utf-8"))
        println(s">>> checkTestDataRunResponse: $response")
        response should not include(s""""schemaName":"ab${testEpoch}""")
      }
    }

    "the step function has not yet run" should {
      val copyTestDataRun =
        lambdaClient.invoke(InvokeRequest.builder().functionName(copyTestDataLambdaName).payload(SdkBytes.fromUtf8String(testEpoch)).build())
      val copyTestDataRunStatus = copyTestDataRun.statusCode()

      "copy test data should run successfully" in {
        copyTestDataRunStatus shouldBe 200
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
      while (describeStatusResponse.status() != ExecutionStatus.SUCCEEDED && describeStatusResponse.status() != ExecutionStatus.FAILED && describeStatusResponse.status() != ExecutionStatus.TIMED_OUT) {
        println(s">>> describeStatusResponse: $describeStatusResponse")
        Thread.sleep(60000)
        describeStatusResponse = sfnClient.describeExecution(executionRequest)
      }

      "complete succesfully" in {
        describeStatusResponse.status() shouldBe ExecutionStatus.SUCCEEDED
      }
    }

    "checking for data" should {
      "return data as expected" in {
        val checkTestDataRun = lambdaClient.invoke(InvokeRequest.builder().functionName(checkTestDataLambdaName).payload(SdkBytes.fromUtf8String(testEpoch)).build())
        val response = checkTestDataRun.payload().asString(Charset.forName("utf-8"))
        println(s">>> checkTestDataRun: status: ${checkTestDataRun.statusCode()}, responce: $response")
        checkTestDataRun.statusCode() shouldBe 200
        response should include (s""""schemaName":"ab${testEpoch}""")
      }
    }
  }
}
