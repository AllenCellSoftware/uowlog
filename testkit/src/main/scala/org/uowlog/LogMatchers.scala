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

import org.scalatest.Matchers
import org.scalatest.enablers.Messaging
import org.scalatest.matchers.{BeMatcher, HavePropertyMatchResult, HavePropertyMatcher, MatchResult, Matcher}
import org.scalatest.words.ResultOfMessageWordApplication
import org.slf4j.event.Level

import scala.collection.JavaConversions._
import scala.util.Try

import spray.json._

trait LogMatchers extends Matchers {
  import DefaultJsonProtocol._
  implicit val LevelReader = JsonReader.func2Reader[Level] { case JsString(s) => Level.valueOf(s) }

  private[uowlog] def genMatcher[T: JsonReader](field: String) = (expected: T) => new HavePropertyMatcher[UOWTestAppender.Entry, T] {
    def apply(entry: UOWTestAppender.Entry) = HavePropertyMatchResult(entry.get[T](field) equals expected, field, expected, entry.get[T](field))
  }

  val time    = genMatcher[String]("time")
  val logType = genMatcher[String]("type")
  val level   = genMatcher[Level] ("level")
  val host    = genMatcher[String]("host")
  val program = genMatcher[String]("program")
  val process = genMatcher[Int]   ("process")
  val thread  = genMatcher[String]("thread")
  def provenance(expected: ProvenanceId): HavePropertyMatcher[UOWTestAppender.Entry, ProvenanceId] = new HavePropertyMatcher[UOWTestAppender.Entry, ProvenanceId] {
    def apply(entry: UOWTestAppender.Entry) = HavePropertyMatchResult(entry.provenanceId equals expected, "provenance", expected, entry.provenanceId)
  }
  def provenance(expected: String): HavePropertyMatcher[UOWTestAppender.Entry, String] = new HavePropertyMatcher[UOWTestAppender.Entry, String] {
    def apply(entry: UOWTestAppender.Entry) = HavePropertyMatchResult(entry.get[String]("provenance") equals expected, "provenance", expected, entry.get[String]("provenance"))
  }
  def provenanceDescendedFrom(expected: ProvenanceId): HavePropertyMatcher[UOWTestAppender.Entry, ProvenanceId] = new HavePropertyMatcher[UOWTestAppender.Entry, ProvenanceId] {
    def apply(entry: UOWTestAppender.Entry) = HavePropertyMatchResult(entry.provenanceId isDescendantOf expected, "provenance", expected, entry.provenanceId)
  }
  def provenanceDescendedFrom(expected: String): HavePropertyMatcher[UOWTestAppender.Entry, ProvenanceId] = provenanceDescendedFrom(ProvenanceId.create(expected))
}

trait UOWMatchers extends LogMatchers {
  import DefaultJsonProtocol._
  implicit val UOWResultReader = JsonReader.func2Reader[UOWResult] { case JsString(s) => UOWResult.valueOf(s) }

  val name   = genMatcher[String]   ("name")
  val result = genMatcher[UOWResult]("result")

  def valueNamed(key: String) = new HavePropertyMatcher[UOWTestAppender.Entry, String] {
    def apply(entry: UOWTestAppender.Entry) = {
      val values = (if (entry.json.fields.contains("values")) entry.get[JsObject]("values").fields else Map[String,String]())
      HavePropertyMatchResult(values.contains(key), "value named", key, values.keys.mkString(", "))
    }
  }

  def metricNamed(key: String) = new HavePropertyMatcher[UOWTestAppender.Entry, String] {
    def apply(entry: UOWTestAppender.Entry) = {
      val metrics = (if (entry.json.fields.contains("metrics")) entry.get[JsObject]("metrics").fields else Map[String,String]())
      HavePropertyMatchResult(metrics.contains(key), "metric named", key, metrics.keys.mkString(", "))
    }
  }

  val closed = BeMatcher[UnitOfWork]{ uow =>
    val pending = uow.getPending
    new MatchResult(uow.closed,
                    s"UnitOfWork ${uow.name} was not closed" +(if (pending.nonEmpty) pending.mkString(" (with pending executions from ", ", ", ")") else ""),
                    s"UnitOfWork ${uow.name} was closed" +(if (pending.nonEmpty) pending.mkString(" (with pending executions from ", ", ", ")") else ""))
  }
}

trait EventMatchers extends LogMatchers {
  import DefaultJsonProtocol._

  val srcClass = genMatcher[String]("class")
  val method   = genMatcher[String]("method")
  val file     = genMatcher[String]("file")

  implicit val logEntryMessage = new org.scalatest.enablers.Messaging[UOWTestAppender.Entry] {
    def messageOf(obj: UOWTestAppender.Entry) = obj.json.fields.getOrElse("message",JsString("<No message logged>")).convertTo[String]
  }
  implicit def resultOfMessageWordApplication2HavePropertyMatcher[T : scala.reflect.ClassTag : Messaging](romwa: ResultOfMessageWordApplication): HavePropertyMatcher[T, String] = romwa(of[T])

  def messageContaining(expected: String) = {
    new HavePropertyMatcher[UOWTestAppender.Entry, String] {
      def apply(le: UOWTestAppender.Entry) = {
        val actual = le.json.fields.getOrElse("message",JsString("<No message logged>")).convertTo[String]
        HavePropertyMatchResult(actual contains expected, "message", expected, actual)
      }
    }
  }
}
