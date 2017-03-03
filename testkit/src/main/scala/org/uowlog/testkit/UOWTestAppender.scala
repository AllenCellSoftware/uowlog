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

import ch.qos.logback.classic.spi._
import ch.qos.logback.core._
import ch.qos.logback.core.encoder.LayoutWrappingEncoder

import java.io.ByteArrayOutputStream
import java.util.ConcurrentModificationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate

import org.uowlog._

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.util.Try

import spray.json._

object UOWTestAppender {
  case class Metric(total: Double, count: Int, units: String)

  class Entry(val raw: String) {
    import DefaultJsonProtocol._

    implicit val metricFormat = jsonFormat3(Metric)

    lazy val json         = raw.parseJson.asJsObject
    lazy val provenanceId = ProvenanceId.create(get[String]("provenance"))

    def get[T : JsonReader](key: String) = apply(key).convertTo[T]
    def apply(key: String) = key.split("/").foldLeft(json: JsValue) { (js, chunk) => js match {
      case obj: JsObject => obj.fields(chunk)
      case arr: JsArray  => arr.elements(chunk.toInt)
      case _ => throw new IllegalArgumentException(s"JsObject expected, have ${js.getClass.getSimpleName}")
    }}

    def values(key: String)  = get[String]("values/" + key)
    def metrics(key: String) = get[Metric]("metrics/" + key)

    override def toString() = raw
  }

  implicit def functionToPredicate[T](func: T => Boolean) = new Predicate[T] { def test(x: T) = func(x) }

  val activeTests = new TrieMap[ProvenanceId, (Int, Option[Int])]
  val sequenceNum = new AtomicInteger

  def clearTests() { activeTests.clear() }
  def startTest (id: ProvenanceId) {
    val seq = sequenceNum.getAndIncrement
    activeTests += (id -> (seq -> None))
  }
  def finishTest(id: ProvenanceId) {
    val end = sequenceNum.getAndIncrement
    activeTests.get(id) match {
      case Some((start, None)) => 
        activeTests(id) = (start -> Some(end))
        val living = activeTests.values.collect{ case (n, None) => n }
        if (living.isEmpty) {
          activeTests.clear()
        } else {
          val oldestLiving = living.min
          activeTests --= activeTests.collect{ case (oid, (_, Some(oe))) if oe < oldestLiving => oid }
        }
      case _ => ()
    }
  }
}

class UOWTestAppender extends OutputStreamAppender[ILoggingEvent] {
  setEncoder { 
    val lwe = new LayoutWrappingEncoder[ILoggingEvent]
    lwe.setLayout(new MonitorLayout)
    lwe
  }
  val baos = new ByteArrayOutputStream
  setOutputStream(baos)

  val allMessages = new ConcurrentLinkedQueue[UOWTestAppender.Entry]
  def clear() { allMessages.clear() }
  def orphanMessages = allMessages.filter(entry => !UOWTestAppender.activeTests.keys.exists(prov => entry.provenanceId == prov || entry.provenanceId.isDescendantOf(prov)))
  def otherTests = if (UnitOfWork.current ne null) (UOWTestAppender.activeTests - UnitOfWork.current.provenanceId).keys.toList else UOWTestAppender.activeTests.keys.toList
  def messages = {
    val others = otherTests
    allMessages.filter(entry => !others.exists(prov => entry.provenanceId == prov || entry.provenanceId.isDescendantOf(prov)))
  }

  override def writeOut(event: ILoggingEvent) {
    baos.reset()
    super.writeOut(event)
    allMessages add new UOWTestAppender.Entry(baos.toString)
  }
}
