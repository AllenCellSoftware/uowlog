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

package org.uowlog.http

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.stream.{ActorMaterializer, StreamTcpException}
import akka.testkit._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.time._
import org.uowlog._
import scala.concurrent.Await
import scala.concurrent.duration._

class HttpClientSpec extends TestKit(ActorSystem("HttpClientSpec", ConfigFactory.parseString("""
  not_here.http {
    host = "nowhere.corp.alleninstitute.org"
    port = 12345
  }
  rejecting.http {
    host = "localhost"
    port = 12345
  }
""").withFallback(ConfigFactory.load))) with FunSpecLike with Matchers with ScalaFutures with Eventually with PatienceConfiguration with Inspectors with LogFileCapture with UOWMatchers {
  // import DefaultJsonProtocol._
  // import org.alleninstitute.httpclient.Protocol._

  implicit val materializer = ActorMaterializer()
  override implicit def patienceConfig = PatienceConfig(scaled(Span(1, Second)), scaled(Span(50, Millis)))

  describe("HttpClient") {
    it("should report an exception if trying to connect to a host that doesn't exist") {
      val client = new HttpClient() { val serviceName = "not_here" }
      val ping = client.sendRequest("ping", Get("/health/ping"))
      ping.failed.futureValue shouldBe a [StreamTcpException]
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have (logType("UOW"), name("ping"), result(UOWResult.EXCEPTION), valueNamed("exception"))
        }
      }
    }
    it("should report an exception if connection is refused") {
      val client = new HttpClient() { val serviceName = "rejecting" }
      val ping = client.sendRequest("ping", Get("/health/ping"))
      ping.failed.futureValue shouldBe a [StreamTcpException]
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have (logType("UOW"), name("ping"), result(UOWResult.EXCEPTION), valueNamed("exception"))
        }
      }
    }
    it("should wrap the request in a UOW with provenance derived from the caller's provenance") {
      val client = new HttpClient() { val serviceName = "not_here" }
      val uow = UnitOfWork("outside")
      val ping = uow { client.sendRequest("ping", Get("/health/ping")) }
      ping.failed.futureValue shouldBe a [StreamTcpException]
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have (logType("UOW"), name("ping"), provenanceDescendedFrom(uow.provenanceId))
        }
      }
    }
  }
}
