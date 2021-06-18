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
import doobie.Transactor
import doobie.implicits._
import doobie.util.transactor.Transactor.Aux
import me.lamouri.JCredStash
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.{DescribeExecutionRequest, ExecutionStatus, StartExecutionRequest}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.Random

class IngestSpec extends AsyncWordSpec with Matchers {

  private val credstashTableName = "credential-store"
  private val context: java.util.Map[String, String] =
    Map("role" -> "address_lookup_file_download").asJava

  private def retrieveCredentials(credential: String) = {
    val credStash = new JCredStash()
    credStash.getSecret(credstashTableName, credential, context)
  }

  "Data Ingest" should {
    val testDataLambdaName = "addressLookupCopyTestDataLambdaFunction"
    val stepFunctionName   = "addressLookupIngestStateMachine"
    val testEpoch = (Random.nextInt(9999) + 500).toString //Make sure that we dont have an actual epoch number
    val xtor: Aux[IO, Unit] = transactor

    "setup test data" when {
      "copyTestData function invoked" in {
        val lambdaClient: LambdaClient = LambdaClient.create()

        val copyTestDataRun =
          lambdaClient.invoke(InvokeRequest.builder().functionName(testDataLambdaName).payload(SdkBytes.fromUtf8String(testEpoch)).build())
        val copyTestDataRunStatus = copyTestDataRun.statusCode()
        copyTestDataRunStatus shouldBe 200
      }

      "verify schema does not exist yet" in {

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

      "run the ingest step function" in {
        val sfnClient: SfnClient = SfnClient.create()

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

        describeStatusResponse.status() shouldBe ExecutionStatus.SUCCEEDED
      }

      "check address_lookup is setup correctly" in {
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

      "data in the view looks ok" in {
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
}
