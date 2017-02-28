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

import ch.qos.logback.classic._
import ch.qos.logback.classic.spi._
import ch.qos.logback.core._
import ch.qos.logback.core.encoder._
import ch.qos.logback.core.rolling._
import ch.qos.logback.core.spi._
import ch.qos.logback.core.util.FileSize
import com.typesafe.config._
import org.slf4j.Logger
import scala.collection.JavaConversions._
import scala.util.control.NonFatal

/**
 * Automatic configurator for Logback.
 * This Configurator sets up time-based file rolling using the JSON layout for UOW logs.
 *
 * Various options can be configured via [[https://github.com/typesafehub/config Typesafe Config]], using the following keys:
 * {{{
 * uowlog.program
 * uowlog.removeDomainSuffix
 * uowlog.loggerLevel
 * uowlog.file
 * uowlog.fileNamePattern
 * uowlog.maxHistory
 * uowlog.totalSizeCap
 * uowlog.cleanHistoryOnStart
 * uowlog.prudent
 * uowlog.level
 * }}}
 * `uowlog.group` and `uowlog.program` are used in the ProvenanceId generation for filling the first two fields of the base id.
 * `uowlog.program` is also used in the default fileNamePattern (if you don't set one yourself), as well as in the program field of each log line.
 * `uowlog.removeDomainSuffix` will trim the logged hostname if it ends with the provided value.
 * `uowlog.loggerLevel` holds a map of logger names to levels, to allow setting explicit levels for particular parts of the logger hierarchy.
 * See [[http://logback.qos.ch/manual/appenders.html#FileAppender]] for information on what all the other values mean.
 *
 * Alternately, this Configurator can be completely bypassed by providing your own logback.xml resource
 * (or any other config file that would be read by logback before falling back on the service provider framework).
 */
class LogbackConfigurator extends ContextAwareBase with Configurator {
  def configure(lc: LoggerContext) { configure(lc, ConfigFactory.load) }

  def configure(lc: LoggerContext, conf: Config) {
    // Avoid re-configuring logging if it's already been configured with the same Config
    // If we do re-configure, then that may interfere with LogFileCapture
    if (LogbackConfigurator.lastConfig == conf) return
    LogbackConfigurator.lastConfig = conf

    val config = conf.withFallback(ConfigFactory.parseString("""
      uowlog {
        group   = none
        program = application
        prudent = false
        level   = INFO
      }"""))

    ProvenanceId.serviceGroup      = config.getString("uowlog.group")
    ProvenanceId.serviceIdentifier = config.getString("uowlog.program")

    val layout = new MonitorLayout()
    layout.setContext(lc)
    if (config.hasPath("uowlog.program"           )) layout.setProgram           (config.getString("uowlog.program"           ))
    if (config.hasPath("uowlog.removeDomainSuffix")) layout.setRemoveDomainSuffix(config.getString("uowlog.removeDomainSuffix"))
    layout.start()

    val encoder = new LayoutWrappingEncoder[ILoggingEvent]()
    encoder.setContext(lc)
    encoder.setLayout(layout)
    encoder.start()

    val appender = (if (config.hasPath("uowlog.file") && config.getString("uowlog.file") == "-") {
      val appender = new ConsoleAppender[ILoggingEvent]()
      appender.setContext(lc)
      appender.setEncoder(encoder)
      appender
    } else {
      val policy = new TimeBasedRollingPolicy[ILoggingEvent]()
      policy.setContext(lc)
      policy.setFileNamePattern("log/" + config.getString("uowlog.program") + ".%d{yyyy-MM-dd_HH, UTC}.log.gz")
      if (config.hasPath("uowlog.fileNamePattern"    )) policy.setFileNamePattern    (config.getString ("uowlog.fileNamePattern"    ))
      if (config.hasPath("uowlog.maxHistory"         )) policy.setMaxHistory         (config.getInt    ("uowlog.maxHistory"         ))
      if (config.hasPath("uowlog.cleanHistoryOnStart")) policy.setCleanHistoryOnStart(config.getBoolean("uowlog.cleanHistoryOnStart"))
      if (config.hasPath("uowlog.totalSizeCap"       )) policy.setTotalSizeCap       (FileSize.valueOf(config.getString("uowlog.totalSizeCap")))

      val appender = new RollingFileAppender[ILoggingEvent]()
      appender.setContext(lc)
      appender.setEncoder(encoder)
      appender.setRollingPolicy(policy)
      appender.setTriggeringPolicy(policy)
      if (config.hasPath("uowlog.prudent")) appender.setPrudent(config.getBoolean("uowlog.prudent"))
      if (config.hasPath("uowlog.file"   )) appender.setFile   (config.getString ("uowlog.file"   ))

      policy.setParent(appender)
      policy.start()

      appender
    })
    appender.start()

    val root = lc.getLogger(Logger.ROOT_LOGGER_NAME)
    root.detachAndStopAllAppenders()
    root.addAppender(appender)
    if (config.hasPath("uowlog.level")) root.setLevel(Level.toLevel(config.getString("uowlog.level")))

    val log = lc.getLogger(this.getClass)

    if (config.hasPath("uowlog.loggerLevel")) {
      val trimmed = config.getConfig("uowlog.loggerLevel")
      for { entry <- trimmed.entrySet } {
        try {
          lc.getLogger(entry.getKey).setLevel(Level.toLevel(entry.getValue.unwrapped.toString))
        } catch {
          case NonFatal(e) => log.error("Couldn't set level for logger " + entry.getKey, e)
        }
      }
    }
  }
}
object LogbackConfigurator extends UOWLogging {
  private[uowlog] var lastConfig: Config = null
  def configure(config: Config) {
    val lc = log.asInstanceOf[ch.qos.logback.classic.Logger].getLoggerContext
    new LogbackConfigurator().configure(lc, config)
  }
}
