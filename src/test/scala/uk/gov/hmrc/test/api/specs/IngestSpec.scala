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

package uk.gov.hmrc.test.api.specs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.ListStateMachinesResponse

import scala.collection.JavaConverters._

class IngestSpec extends AsyncWordSpec with Matchers {
  "Bob" should {
    "list step functions" when {
      "list function api is called" in {
        val sfnClient: SfnClient =
          SfnClient
            .builder()
            .build()

        val listStateMachinesResponse: ListStateMachinesResponse = sfnClient.listStateMachines()
        listStateMachinesResponse.stateMachines().asScala.foreach { case sm =>
          println(s" name: ${sm.name()}, arn: ${sm.stateMachineArn()}")
        }
        "a" shouldBe "b"
      }
    }
  }
}
