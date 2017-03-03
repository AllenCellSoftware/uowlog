/*
** Copyright 2017 Allen Institute
** 
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
** 
** http://www.apache.org/licenses/LICENSE-2.0
** 
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
** implied. See the License for the specific language governing
** permissions and limitations under the License.
*/

package org.uowlog.testkit

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.unmarshalling._
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit._
import com.typesafe.config.Config
import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.exceptions.TestFailedException
import org.scalatest.time._
import org.uowlog._
import org.uowlog.http._
import scala.concurrent.Await
import scala.concurrent.duration._

class SimpleClient(config: Config)(implicit system: ActorSystem, materializer: Materializer) extends HttpClient(config) {
  def this()(implicit system: ActorSystem, materializer: Materializer) = this(system.settings.config)
  val serviceName = "simple"
}

class MockHttpServiceSpec extends TestKit(ActorSystem("MockHttpServiceSpec")) with FunSpecLike with Matchers with ScalaFutures with Eventually with PatienceConfiguration with Inspectors with LogFileCapture with UOWMatchers {
  implicit val materializer = ActorMaterializer()
  override implicit def patienceConfig = PatienceConfig(scaled(Span(1, Second)), scaled(Span(50, Millis)))
  import system.dispatcher

  describe("MockHttpService") {
    it("should report success if the client behaviour matches expected") {
      val client = new SimpleClient() with MockHttpService {
        import akka.http.scaladsl.server.Directives._
        val expected = List(
          get(path("health" / "ping")(complete("PONG")))
        )
      }

      client runWithMockService {
        val response = client.sendRequest("ping", Get("/health/ping")).futureValue
        response.status shouldBe StatusCodes.OK
        val body = Await.result(Unmarshal(response.entity).to[String], 1 second)
        body shouldBe "PONG"
      }
    }
    it("should report a test failure if the client sends an unexpected request") {
      val client = new SimpleClient() with MockHttpService {
        import akka.http.scaladsl.server.Directives._
        val expected = List(
          post(path("health" / "ping")(complete("PONG")))
        )
      }

      client runWithMockService {
        val ping = client.sendRequest("ping", Get("/health/ping"))
        ping.failed.futureValue shouldBe a [TestFailedException]
        ping.failed.futureValue.getMessage should (include ("Unexpected request to mock server") and include ("HttpRequest(HttpMethod(GET)") and include ("MethodRejection(HttpMethod(POST))"))
      }
    }
  }
}

