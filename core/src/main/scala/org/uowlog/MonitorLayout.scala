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

import ch.qos.logback.classic.spi.{ILoggingEvent, StackTraceElementProxy}
import ch.qos.logback.core.LayoutBase
import java.time.{Instant, ZoneOffset}
import scala.collection.JavaConversions._

object MonitorLayout {
  final val NAME = "Name"
  final val PROVENANCE = "Provenance"
  final val MONITOR_TYPE = "MonitorType"

  // These are defined to match the ones in akka.event.slf4j.Slf4jEventHandler
  final val THREAD = "sourceThread"
  final val SOURCE = "akkaSource"

  // Java library compatibility
  def escape(obj: AnyRef): String =
    if (obj eq null) null
    else {
      val in = obj.toString

      // Avoid allocation of a new String if we don't need one
      if (!in.exists(c => c < 32 || c > 126 || c == '\\' || c == '"' || c == '\n')) {
        in
      } else {
        // Guess at the expected length to avoid reallocations of the buffer
        val length = in.length()
        val result = new StringBuilder(length + 10)

        // Walk character-by-character; it's simpler than chunking (and probably fast enough)
        var pos = 0
        while (pos < length) {
          val c = in.charAt(pos)
          if      (c == '\\') result.append("\\\\")
          else if (c == '"' ) result.append("\\\"")
          else if (c == '\n') result.append("\\n")
          else if (c == '\t') result.append("\\t")
          else if (c >= 32 && c <= 126) result.append(c)
          pos = pos + 1
        }
        result.toString()
      }
    }
}

/**
 * JSON formatter for log events.
 *
 * This formats each log entry as a single-line JSON object.
 * All log lines contain the following fields:
 * <table>
 *   <tr><th>time</th><td>ISO-8601 formatted timestamp in zone +0 (UTC)</td></tr>
 *   <tr><th>type</th><td>either `EVENT` (for traditional logging) or `UOW` (for [[UnitOfWork]])</td></tr>
 *   <tr><th>level</th><td>one of `ERROR`, `WARN`, `INFO`, `DEBUG`, or `TRACE`</td></tr>
 *   <tr><th>host</th><td>hostname of the machine generating the log; may be trimmed via `setRemoveDomainSuffix`</td></tr>
 *   <tr><th>program</th><td>name of the program generating the log; set via `setProgram`</td></tr>
 *   <tr><th>process</th><td>process id</td></tr>
 *   <tr><th>thread</th><td>thread id</td></tr>
 *   <tr><th>provenance</th><td>[[ProvenanceId]] of the [[UnitOfWork]] (or nearest enclosing UOW for traditional logging)</td></tr>
 * </table>
 * `EVENT` log lines contain the following additional fields:
 * <table>
 *   <tr><th>class</th><td>fully qualified class name of the class doing the logging</td></tr>
 *   <tr><th>method</th><td>name of the method doing the logging</td></tr>
 *   <tr><th>file</th><td>file name and line number (if available) of the logging</td></tr>
 *   <tr><th>message</th><td>log message</td></tr>
 *   <tr><th>exceptions</th><td>(optional) list of exceptions (following the cause links) associated with the logging.  Each list entry is an object containing the exception `message`, `class`, and `stack` (if available).  Stacks for causes are trimmed to avoid duplicate frame output.</td></tr>
 * </table>
 * `UOW` log lines contain the following additional fields:
 * <table>
 *   <tr><th>name</th><td>name of the [[UnitOfWork]]</td></tr>
 *   <tr><th>start</th><td>ISO-8601 formatted timestamp in zone +0 (UTC) of when the [[UnitOfWork]] was created</td></tr>
 *   <tr><th>end</th><td>ISO-8601 formatted timestamp in zone +0 (UTC) of when the [[UnitOfWork]] was closed</td></tr>
 *   <tr><th>result</th><td>one of `SUCCESS`, `FAILURE`, or `EXCEPTION`; see [[UOWResult]] for more detail</td></tr>
 *   <tr><th>values</th><td>a set of key-value pairs recorded in the [[UnitOfWork]], intended as additional detail or cross-cutting dimensions for metric gathering</td></tr>
 *   <tr><th>metrics</th><td>a set of named metrics recorded in the [[UnitOfWork]].  Each metric value is a JSON object containing `total` (the numeric value for the metric), `units` (how the metric is measured), and `count` (the number of times value was added to the metric in this [[UnitOfWork]]).</td></tr>
 * </table>
 * All [[UnitOfWork]]s generate a `duration` metric, measuring the difference (in milliseconds) between `start` and `end`.
 * All UOWs that are tied to code execution also generate an `executionTime` metric, which counts the amount of CPU time used for the UnitOfWork.
 * The `count` portion of the `executionTime` metric indicates how many distinct code blocks were run for the UnitOfWork.
 */
class MonitorLayout() extends LayoutBase[ILoggingEvent] {
  var program:  Option[String] = None
  var hostName: String = MonitoringInfo.hostName

  // Sigh... Java-style instantiation and configuration.  Thanks, logback.
  /** Set the program recorded on each log line.  This can be used in filtering aggregated logs. */
  def setProgram           (s: String) { program = Some(s) }
  /** Remove a specified suffix from the host name.  This is typically used to reduce log size by removing constant portions of the hostnames within an organization, e.g. `.alleninstitute.org`. */
  def setRemoveDomainSuffix(s: String) { if (hostName endsWith s) hostName = hostName.substring(0, hostName.length - s.length) }

  import org.uowlog.MonitorLayout._

  def convertSTEToString(step: StackTraceElementProxy): String = {
    val ste = step.getStackTraceElement
    ste.getClassName + "." + ste.getMethodName + (if (ste.getFileName ne null) "(" + ste.getFileName + (if (ste.getLineNumber >= 0) ":" + ste.getLineNumber else "") + ")" else "")
  }

  def doLayout(loggingEvent: ILoggingEvent): String = {
    val mdc = loggingEvent.getMDCPropertyMap
    val output = new StringBuilder

    @inline def appendString(key: String, value: String) { output.append('"').append(key).append("\":\"").append(value).append("\",") }
    @inline def appendInt   (key: String, value: Int)    { output.append('"').append(key).append("\":"  ).append(value).append(  ",") }

    output append '{'
    appendString("time",       Instant.ofEpochMilli(loggingEvent.getTimeStamp).atZone(ZoneOffset.UTC).toString)
    appendString("type",       mdc.getOrElse(MONITOR_TYPE, "EVENT"))
    appendString("level",      loggingEvent.getLevel.toString)
    appendString("host",       hostName)
    appendString("program",    program getOrElse loggingEvent.getLoggerContextVO.getName)
    appendInt   ("process",    MonitoringInfo.processId)
    appendString("thread",     mdc.getOrElse(THREAD,     loggingEvent.getThreadName()))
    appendString("provenance", mdc.getOrElse(PROVENANCE, if (UnitOfWork.current ne null) UnitOfWork.current.provenanceId.toString else ""))
    if (mdc.getOrElse(MONITOR_TYPE, "EVENT") == "UOW") {
      output append loggingEvent.getMessage
    } else {
      val callerInfo = loggingEvent.getCallerData.dropWhile(ste => ste.getFileName contains "MonitorLayout.").head
      appendString("class", callerInfo.getClassName)
      appendString("method", callerInfo.getMethodName)
      appendString("file", callerInfo.getFileName + ":" + callerInfo.getLineNumber)
      var exception = loggingEvent.getThrowableProxy
      if (exception ne null) {
        output append "\"exceptions\":["
        while (exception ne null) {
          output append '{'
          appendString("message", escape(exception.getMessage))
          output.append("\"class\":\"").append(exception.getClassName).append("\"")
          val stack = exception.getStackTraceElementProxyArray
          if ((stack ne null) && stack.length > 0) {
            val render =
              if (exception.getCommonFrames > 2) {
                exception.getStackTraceElementProxyArray.dropRight(exception.getCommonFrames - 1).map(convertSTEToString) :+
                  s"... omitting ${exception.getCommonFrames - 1} common frames"
              } else {
                exception.getStackTraceElementProxyArray.map(convertSTEToString)
              }
            output append render.map(escape).mkString(",\"stack\":[\"", "\",\"", "\"]")
          }
          exception = exception.getCause
          output.append(if (exception ne null) "}," else "}")
        }
        output append "],"
      }
      output.append("\"message\":\"").append(escape(loggingEvent.getMessage)).append("\"")
    }
    output append "}\n"
    output.toString
  }
}
