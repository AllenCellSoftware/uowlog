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

package org.uowlog

import org.scalatest._
import org.scalatest.Inspectors._
import org.scalatest.concurrent.Eventually
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.uowlog.testkit._
import scala.collection.JavaConversions._
import spray.json._

class MonitorLayoutSpec extends FunSpec with Matchers with Eventually with LogFileCapture with EventMatchers {
  import DefaultJsonProtocol._

  // This is just to get a non-anonymous method name for where the logging happened
  def prettyPosition() { log.info("where") }

  describe("MonitorLayout") {
    it("should be instantiable without constructor arguments") {
      "new MonitorLayout()" should compile
    }
    it("should log in JSON format") {
      log.info("Hello World")

      eventually {
        forAtLeast(1, logFile.messages) { _ should have message "Hello World" }
      }
    }
    it("should log the time in ISO-8601 format") {
      log.info("when")
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have message "when"
          entry.get[String]("time") should fullyMatch regex """\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d\d\dZ"""
        }
      }
    }
    it("should log the host, program, process, and thread identifiers") {
      log.info("ids")
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have (
            message ("ids"),
            host    (MonitoringInfo.hostName),
            program (loggerContext.getName),
            thread  (Thread.currentThread.getName)
          )
          entry.get[Int]("process") shouldBe > (1)
        }
      }
    }
    describe("EVENT logging") {
      it("should log the type of log entry for standard event logs") {
        log.info("what")
        eventually {
          forAtLeast(1, logFile.messages) { entry =>
            entry.get[String]("message") shouldBe "what"
            entry.get[String]("type") shouldBe "EVENT"
          }
        }
      }
      it("should log the level of log entry") {
        val lblog = log.asInstanceOf[ch.qos.logback.classic.Logger]
        val oldLevel = lblog.getLevel
        lblog.setLevel(ch.qos.logback.classic.Level.ALL)
        log.error("e")
        log.warn("w")
        log.info("i")
        log.debug("d")
        log.trace("t")
        lblog.setLevel(oldLevel)
        eventually {
          forAtLeast(1, logFile.messages) { entry => entry should ((have message "e") and (have (level (Level.ERROR)))) }
          forAtLeast(1, logFile.messages) { entry => entry.get[String]("message") shouldBe "w"; entry.get[String]("level") shouldBe "WARN" }
          forAtLeast(1, logFile.messages) { entry => entry.get[String]("message") shouldBe "i"; entry.get[String]("level") shouldBe "INFO" }
          forAtLeast(1, logFile.messages) { entry => entry.get[String]("message") shouldBe "d"; entry.get[String]("level") shouldBe "DEBUG" }
          forAtLeast(1, logFile.messages) { entry => entry.get[String]("message") shouldBe "t"; entry.get[String]("level") shouldBe "TRACE" }
        }
      }
      it("should record the provenanceId of the currently open UnitOfWork") {
        log.info("origin")
        eventually {
          forAtLeast(1, logFile.messages) { entry =>
            entry.get[String]("message") shouldBe "origin"
            entry.get[String]("provenance") shouldBe UnitOfWork.current.provenanceId.toString
          }
        }
      }
      it("should record the class, method, and file where the event happened") {
        prettyPosition()
        eventually {
          forAtLeast(1, logFile.messages) { entry =>
            entry.get[String]("message") shouldBe "where"
            entry.get[String]("class") should startWith (getClass.getName)
            entry.get[String]("method") shouldBe "prettyPosition"
            entry.get[String]("file") should startWith ("MonitorLayoutSpec.scala:")
            entry.get[String]("file").substring("MonitorLayoutSpec.scala:".length).toInt shouldBe > (10)
          }
        }
      }
      it("should record the exception info if provided") {
        log.info("oops", new IllegalStateException("Ontario", new IllegalArgumentException("ad hominem")))
        eventually {
          forAtLeast(1, logFile.messages) { entry =>
            entry.get[String]("message") shouldBe "oops"
            val info = entry.get[Array[JsObject]]("exceptions")
            info should not be (null)
            info should have length (2)
            info(0).fields("class"  ).convertTo[String] shouldBe "java.lang.IllegalStateException"
            info(0).fields("message").convertTo[String] shouldBe "Ontario"
            info(1).fields("class"  ).convertTo[String] shouldBe "java.lang.IllegalArgumentException"
            info(1).fields("message").convertTo[String] shouldBe "ad hominem"
          }
        }
      }
      it("should include stack traces from exceptions, trimming the traces from causes") {
        log.info("oops", new IllegalStateException("Ontario", new IllegalArgumentException("ad hominem")))
        eventually {
          forAtLeast(1, logFile.messages) { entry =>
            entry.get[String]("message") shouldBe "oops"
            val info = entry.get[Array[JsObject]]("exceptions")
            val s0 = info(0).fields("stack").convertTo[Array[String]]
            val s1 = info(1).fields("stack").convertTo[Array[String]]
            s1 should have length (2)
            s1(1) should startWith ("... omitting")
            atLeast(1, s0) shouldBe s1(0)
            all(s0) should not startWith ("... omitting")
          }
        }
      }
    }
  }
}
