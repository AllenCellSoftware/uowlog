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
import org.slf4j.event.Level
import spray.json._

class LogMatchersSpec extends FunSpec with Matchers with LogMatchers with EventMatchers with UOWMatchers {
  val simpleEvent = new UOWTestAppender.Entry("""{"time":"2017-01-04T19:08:17.803Z","type":"EVENT","level":"INFO","host":"alexp-desktop","program":"default","process":3040,"thread":"pool-1-thread-1-ScalaTest-running-MonitorLayoutSpec","provenance":"none.application.40fbadbd-4f41-4d16-8295-2a114195f568:10,2","class":"org.uowlog.MonitorLayoutSpec","method":"$anonfun$new$9","file":"MonitorLayoutSpec.scala:37","message":"ids"}""")
  val simpleUOW = new UOWTestAppender.Entry("""{"time":"2017-01-06T01:12:34.687Z","type":"UOW","level":"INFO","host":"alexp-desktop","program":"default","process":3918,"thread":"default-akka.actor.default-dispatcher-2","provenance":"none.application.883b183f-71f4-4445-b52d-193f4f3fcad0:1","name":"tellBox","start":"2017-01-06T01:12:33.685Z","end":"2017-01-06T01:12:34.687Z","result":"NORMAL","values":{"status":"awake"},"metrics":{"duration":{"total":1002.0,"count":1,"units":"ms"},"executionTime":{"total":1.0,"count":2,"units":"ms"}}}""")

  describe("LogMatchers") {
    it("should match the time properly") {
      simpleEvent should have (time ("2017-01-04T19:08:17.803Z"))
      simpleEvent should not (have (time ("2016-01-01T00:00:00.000Z")))
    }
    it("should match the type properly") {
      simpleEvent should have (logType ("EVENT"))
      simpleEvent should not (have (logType ("UOW")))
    }
    it("should match the level properly") {
      simpleEvent should have (level (Level.INFO))
      simpleEvent should not (have (level (Level.ERROR)))
    }
    it("should match the host properly") {
      simpleEvent should have (host ("alexp-desktop"))
      simpleEvent should not (have (host ("aics-00")))
    }
    it("should match the program properly") {
      simpleEvent should have (program ("default"))
      simpleEvent should not (have (program ("fss")))
    }
    it("should match the process properly") {
      simpleEvent should have (process (3040))
      simpleEvent should not (have (process (3123)))
    }
    it("should match the thread properly") {
      simpleEvent should have (thread ("pool-1-thread-1-ScalaTest-running-MonitorLayoutSpec"))
      simpleEvent should not (have (thread ("fss")))
    }
    it("should match the provenance properly") {
      simpleEvent should have (provenance ("none.application.40fbadbd-4f41-4d16-8295-2a114195f568:10,2"))
      simpleEvent should not (have (provenance ("none.application.40fbadbd-4f41-4d16-8295-2a114195f568:")))
      simpleEvent should have (provenanceDescendedFrom ("none.application.40fbadbd-4f41-4d16-8295-2a114195f568:10"))
      simpleEvent should have (provenanceDescendedFrom ("none.application.40fbadbd-4f41-4d16-8295-2a114195f568:"))
      simpleEvent should not (have (provenanceDescendedFrom ("none.application.40fbadbd-4f41-4d16-8295-2a114195f568:10,2")))
      simpleEvent should not (have (provenanceDescendedFrom ("none.application.40fbadbd-4f41-4d16-8295-2a114195f568:1")))
      simpleEvent should not (have (provenanceDescendedFrom ("none.application.40fbadbd-4f41-4d16-8295-2a114195f569:")))
    }
  }

  describe("EventMatchers") {
    it("should match the class properly") {
      simpleEvent should have (srcClass ("org.uowlog.MonitorLayoutSpec"))
      simpleEvent should not (have (srcClass ("org.uowlog.LogMatchersSpec")))
    }
    it("should match the method properly") {
      simpleEvent should have (method ("$anonfun$new$9"))
      simpleEvent should not (have (method ("hello")))
    }
    it("should match the file properly") {
      simpleEvent should have (file ("MonitorLayoutSpec.scala:37"))
      simpleEvent should not (have (file ("org.uowlog.LogMatchersSpec")))
    }
    it("should match the message properly") {
      simpleEvent should have message ("ids")
      simpleEvent should not (have message ("nothing"))
      simpleEvent should have (messageContaining ("id"))
      simpleEvent should not (have (messageContaining ("ID")))
    }
  }

  describe("UOWMatchers") {
    it("should match UOW name properly") {
      simpleUOW should have (name ("tellBox"))
      simpleUOW should not (have (name ("small")))
    }
    it("should match UOW result properly") {
      simpleUOW should have (result (UOWResult.NORMAL))
      simpleUOW should not (have (result (UOWResult.FAILURE)))
    }
    it("should match values properly") {
      simpleUOW should have (valueNamed ("status"))
      simpleUOW should not (have (valueNamed ("duration")))
    }
    it("should match metrics properly") {
      simpleUOW should have (metricNamed ("duration"))
      simpleUOW should not (have (metricNamed ("status")))
    }
  }
}
