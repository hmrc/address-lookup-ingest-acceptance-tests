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

import me.lamouri.JCredStash
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.{DescribeExecutionRequest, ExecutionStatus, ListStateMachinesResponse, StartExecutionRequest}

import java.sql.DriverManager
import scala.collection.JavaConverters._
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
      val testEpoch = (Random.nextInt(9999) + 500).toString //Make sure that we dont have an actual epoch number

    "setup test data" when {
      "copyTestData function invoked" in {
        val lambdaClient: LambdaClient = LambdaClient.create()

        val copyTestDataRun =
          lambdaClient.invoke(InvokeRequest.builder().functionName("addressLookupCopyTestDataLambdaFunction").payload(SdkBytes.fromUtf8String(testEpoch)).build())
        val copyTestDataRunStatus = copyTestDataRun.statusCode()
        copyTestDataRunStatus shouldBe 200
      }
      "run the ingest step function" in {
        val sfnClient: SfnClient = SfnClient.create()
        val startExecutionRequest =
          StartExecutionRequest
              .builder()
              .stateMachineArn("arn:aws:states:eu-west-2:710491386758:stateMachine:addressLookupIngestStateMachine")
              .input(s"$testEpoch")
              .build()
        val executeSfnResponse = sfnClient.startExecution(startExecutionRequest)
        val executionArn = executeSfnResponse.executionArn()

        val executionRequest = DescribeExecutionRequest.builder().executionArn(executionArn).build()
        var describeStatusResponse = sfnClient.describeExecution(executionRequest)
        while (describeStatusResponse.status() != ExecutionStatus.SUCCEEDED) {
          Thread.sleep(60000)
          describeStatusResponse = sfnClient.describeExecution(executionRequest)
        }

        describeStatusResponse.status() shouldBe ExecutionStatus.SUCCEEDED
      }
    }

    "return test data" when {
      "database is queried" in {
        val host: String = retrieveCredentials("address_lookup_rds_host")
        val port: String = "5445"
        val database: String = retrieveCredentials("address_lookup_rds_database")
        val admin: String = retrieveCredentials("address_lookup_rds_readonly_user")
        val adminPassword: String = retrieveCredentials("address_lookup_rds_readonly_password")

        Class.forName("org.postgresql.Driver")
        val dbUrl = s"jdbc:postgresql://localhost:$port/$database?searchpath=public"
        val connection = DriverManager.getConnection(dbUrl, admin, adminPassword)
        val stmt = connection.createStatement()
        val resultSet = stmt.executeQuery("select * from address_lookup where postcode like 'BT12 %' limit 10")
        resultSet.next() shouldBe true
        resultSet.getString("postcode") should startWith("BT")

        val descResult = stmt.executeQuery(
          """select schemaname, viewname, viewowner, definition
            |from pg_views
            |where viewname = 'address_lookup'
            |and schemaname = 'public'""".stripMargin)
        descResult.next() shouldBe true
        val viewDefinition = descResult.getString("definition")
        connection.close()
        viewDefinition should include(s"FROM ab${testEpoch}_")
      }
    }
  }
}
