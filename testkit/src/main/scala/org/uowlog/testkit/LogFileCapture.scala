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

import ch.qos.logback.classic._
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core._
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import org.scalatest.{Args, BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import org.uowlog._
import scala.collection.JavaConversions._
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{Success, Failure}

trait LogFileCapture extends BeforeAndAfterAll with BeforeAndAfterEach with UOWLogging { this: Suite =>
  /** Override this to true to get log files dumped after each failure */
  def noisy = false

  lazy val loggerContext = org.slf4j.LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

  lazy val logFile = {
    val name = getClass.getName.replaceAll(".*\\.", "")

    val appender = new UOWTestAppender
    appender.setContext(loggerContext)
    appender.setName(name)
    appender.start()

    appender
  }
  @tailrec final def root: Logger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) match {
    case l: Logger => l
    case _ => Thread.sleep(100); root
  }

  override protected def beforeAll {
    super.beforeAll()
    for {
      appender <- root.iteratorForAppenders
      if !(appender.isInstanceOf[UOWTestAppender])
    } root.detachAppender(appender)
    root.addAppender(logFile)
  }

  override protected def beforeEach {
    logFile.clear()
    super.beforeEach()
  }

  override protected def afterAll {
    root.detachAppender(logFile)
    super.afterAll()
  }

  // Make sure that all tests run within a unit of work.
  // This also allows the logged data to be filtered by provenance,
  // to make it easier to spot discontinuities in provenance chains.
  abstract override protected def runTest(testName: String, args: Args) = {
    UnitOfWork(testName)(log) {
      val prov = UnitOfWork.current.provenanceId
      UOWTestAppender.startTest(prov)
      val status = super.runTest(testName, args)
      status.whenCompleted { 
        case Success(false) | Failure(_) =>
          if (noisy) {
            println("vvvv==== log file ====vvvv")
            logFile.allMessages.foreach(print)
            println("^^^^==== log file ====^^^^")
          }
        case _ => ()
      }
      status.withAfterEffect { UOWTestAppender.finishTest(prov) }
    }
  }

  def logfileErrorMessages = {
    Thread.sleep(1)
    logFile.messages.filter { s => s.raw.contains("EVENT") && s.raw.contains("ERROR") }
  }

  def logfileWarnMessages = {
    Thread.sleep(1)
    logFile.messages.filter { s => s.raw.contains("EVENT") && s.raw.contains("WARN") }
  }

  def printLogMessages() {
    logFile.messages.foreach(e => println(e.raw))
  }
}
