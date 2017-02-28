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

import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic._

import scala.util.{Random, Try}

object ProvenanceId {
  var serviceGroup: String = ""
  var serviceIdentifier: String = ""

  def getServiceGroup      = ProvenanceId.serviceGroup
  def getServiceIdentifier = ProvenanceId.serviceIdentifier

  def create(): ProvenanceId = create(serviceGroup, serviceIdentifier)
  def create(svcGroup: String, svcId: String): ProvenanceId = new ProvenanceId(svcGroup + "." + svcId + "." + UUID.randomUUID() + ":")
  def create(base: String): ProvenanceId = { validate(base); new ProvenanceId(base) }

  private val tailSpec = """\A(?:[1-9]\d*[rm]?(?:,[1-9]\d*[rm]?)*)?\Z""".r

  def validate(id: String) {
    if (id eq null) throw new IllegalArgumentException("ProvenanceId must not be null")
    val firstColon = id.indexOf(':')
    if (firstColon < 0) throw new IllegalArgumentException("Missing ':' in ProvenanceId " + id)
    val secondColon = id.indexOf(':', firstColon + 1)
    if (firstColon < 0) throw new IllegalArgumentException("Extra ':' in ProvenanceId " + id)
    val firstDot = id.indexOf('.')
    if (firstDot < 0 || firstDot > firstColon) throw new IllegalArgumentException("Missing group serparator '.' in ProvenanceId " + id)
    val secondDot = id.indexOf('.', firstDot + 1)
    if (secondDot < 0 || secondDot > firstColon) throw new IllegalArgumentException("Missing service serparator '.' in ProvenanceId " + id)
    if (tailSpec.findFirstIn(id.substring(firstColon + 1)) == None) throw new IllegalArgumentException("Illegal call chain in ProvenanceId " + id)
  }
}

case class ProvenanceId private[uowlog] (val representation: String) extends Ordered[ProvenanceId] {
  private val nextChildId = new AtomicInteger(1)
  val topLevel = representation endsWith ":"
  def isMulticast = representation endsWith "m"

  def createNext(pClass: ProvenanceClass) =
    new ProvenanceId(representation + (if (topLevel) "" else ",") + (if (isMulticast) Random.nextInt(100000) else nextChildId.getAndIncrement) + pClass.toString)

  def isDescendantOf(other: ProvenanceId) =
    (other.representation.length < representation.length) &&
      (if (other.representation.endsWith(":"))
        representation.startsWith(other.representation)
      else
        representation.startsWith(other.representation + ","))

  override def toString = representation

  def compare(that: ProvenanceId) = NaturalOrdering.compare(this, that)

  def root = if (topLevel) this else new ProvenanceId(representation.substring(0, representation.indexOf(':') + 1))
}

// More Java API stuff
object MonitoringInfo {
  def getServiceGroup                 = ProvenanceId.serviceGroup
  def setServiceGroup     (s: String) { ProvenanceId.serviceGroup = s }
  def getServiceIdentifier            = ProvenanceId.serviceIdentifier
  def setServiceIdentifier(s: String) { ProvenanceId.serviceIdentifier = s }

  lazy val (processId, hostName) = {
    val managementName = java.lang.management.ManagementFactory.getRuntimeMXBean.getName
    val parts = managementName.split("@")
    (parts(0).toInt, parts(1))
  }
  def getHostName  = hostName
  def getProcessId = processId

  lazy val ipAddress = Try { InetAddress.getLocalHost().getHostAddress() }.getOrElse("0.0.0.0")
  def getIPAddress = ipAddress
}
