package uk.gov.hmrc

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.ListStateMachinesResponse

import scala.collection.JavaConverters._

class StepFunctionSpec extends AsyncWordSpec with Matchers {
  "Bob" should {
    "list step functions" when {
      "list function api is called" in {
        val sfnClient: SfnClient =
          SfnClient
            .builder()
            .region(Region.EU_WEST_2)
            .credentialsProvider(
              DefaultCredentialsProvider
                .builder()
                .profileName("txm-integration")
                .build()
            )
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
