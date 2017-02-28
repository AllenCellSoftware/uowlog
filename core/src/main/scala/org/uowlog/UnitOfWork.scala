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

import java.util.concurrent.atomic._
import java.time._
import java.time.Duration // must be explicit to avoid ambiguity with scala.concurrent.duration.Duration

import org.uowlog.UOWResult._
import org.uowlog.ProvenanceClass._
import org.slf4j.{Logger, MDC}
import org.slf4j.event.Level
import org.slf4j.event.Level._

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

object UnitOfWork extends UOWLogging {
  class Metric(val units: String) {
    var value: Double = 0
    var count: Int = 0
  }

  /** Easy-access functions for the common UnitOfWork manipulations.
   * The manipulations are applied to the current UnitOfWork.
   * This is a class instead of a trait for simpler Java integration,
   * and to encourage people to `import UnitOfWork.functions._` instead of mixing this in to their own classes. */
  class Functions {
    def addMetric(metricName: String, measuredValue: Double, metricUnit: String)             = UnitOfWork.current.addMetric(metricName, measuredValue, metricUnit)
    def addMetric(metricName: String, measuredValue: Double, metricUnit: String, count: Int) = UnitOfWork.current.addMetric(metricName, measuredValue, metricUnit, count)
    def createNextProvenanceId(pClass: ProvenanceClass)                                      = UnitOfWork.current.provenanceId.createNext(pClass)
    def recordException(e: Throwable)                                                        = UnitOfWork.current.recordException(e)
    def recordException[T](pf: PartialFunction[Throwable, T])                                = UnitOfWork.current.recordException(pf)
    def recordValue(key: String, value: String)                                              = UnitOfWork.current.recordValue(key, value)
    def setResult(result: UOWResult)                                                         = UnitOfWork.current.setResult(result)

    /** Convenience wrapper for applying a UOW to a block, without having to say the log explicitly.
     * This is effectively just a reordering of the argument groups. */
    def withUnitOfWork[T](
      name: String,
      parent: UnitOfWork = current,
      provenanceId: ProvenanceId = null,
      level: Level = null.asInstanceOf[Level],
      defaultResult: UOWResult = NORMAL,
      exceptionResult: UOWResult = EXCEPTION,
      traceExecutionSources: Boolean = false,
      pendingComplaintInterval: scala.concurrent.duration.Duration = 5.minutes
    )(f: => T)(implicit log: Logger): T =
      UnitOfWork(name, parent, provenanceId, level, defaultResult, exceptionResult, traceExecutionSources, pendingComplaintInterval)(log)(f)
  }

  /** Importable version of the easy-access functions for the common UnitOfWork manipulations. */
  object functions extends Functions

  val OVERRIDE_PROVENANCE    = "Nested UOW provenanceId %s masks parent UOW provenanceId %s"
  val OVERRIDE_RECORD_VALUE  = "recordValue called multiple times on id %s"
  val OVERRIDE_METRIC_UNITS  = "addMetric called with incompatible units on id %s"
  val CLOSED_RECORD_VALUE    = "recordValue called on closed UOW %s"
  val CLOSED_ADD_METRIC      = "addMetric called on closed UOW %s"
  val MULTIPLE_CLOSE_WARNING = "Unit of work %s closed more than once"
  val PENDING_CLOSE_WARNING  = "Unit of work %s closed with pending executions"

  /** Test support for arbitrarily setting the current time for UnitOfWork operations. */
  var mockTime: Option[Long] = None
  /** Test support for arbitrarily setting the current time for UnitOfWork operations. */
  def setNow(when: Instant) { if (when eq null) mockTime = None else mockTime = Some(when.toEpochMilli) }
  /** Get the current time for UnitOfWork operations, respecting the values possibly set for testing. */
  def getCurrentTime = mockTime getOrElse System.currentTimeMillis

  private[uowlog] val activeUOWs = new ThreadLocal[UnitOfWork]
  /** Get the current UnitOfWork.
   * When a UnitOfWork is applied to some code, the UnitOfWork is current within that code's execution.
   * Alternately, this can be thought of as searching the call stack for the closest enclosing UnitOfWork.
   * It's actually implemented with a ThreadLocal and a bunch of magic to carry the UOW across commonly used asynchronous execution boundaries.
   */
  def current = activeUOWs.get

  /** Generate a child ProvenanceId of the current UnitOfWork.
   * If there is no current UOW, then a root ProvenanceId is generated.
   */
  def createNextProvenanceId(pClass: ProvenanceClass, parent: UnitOfWork = current) = {
    if (parent eq null)
      if (pClass == LOCAL)
        ProvenanceId.create
      else
        ProvenanceId.create.createNext(pClass)
    else
      parent.provenanceId.createNext(pClass)
  }

  private[uowlog] def nextLogLevel(parent: UnitOfWork = current) =
    if (parent == null) INFO else parent.level

  /** Make a new UnitOfWork with a given name.
   * All other parameters have sane defaults.
   * Idiomatically, this is used as either:
   * {{{
   * UnitOfWork("nameLabellingTheEnclosedCode")(log) {
   *   ... code ...
   * }
   * }}}
   * or
   * {{{
   * val uow = UnitOfWork("nameForManuallyTrackedUOW")
   * ... code ...
   * uow.close()
   * }}}
   * The first version is strongly encouraged, because it:
   * - automatically tracks asynchronous execution,
   * - tracks provenance for nested UOWs,
   * - enables the shorthand manipulation functions without passing the UOW reference around everywhere.
   *
   * You can also use the {{{withUnitOfWork}}} function (in {{{UnitOfWork.functions}}}) as an alternative to the first version,
   * to reorder the arguments so that you don't have to explicitly pass the logger.
   */
  final def apply(
    name: String,
    parent: UnitOfWork = current,
    provenanceId: ProvenanceId = null,
    level: Level = null.asInstanceOf[Level],
    defaultResult: UOWResult = NORMAL,
    exceptionResult: UOWResult = EXCEPTION,
    traceExecutionSources: Boolean = false,
    pendingComplaintInterval: scala.concurrent.duration.Duration = 5.minutes
  )(implicit log: Logger): UnitOfWork =
    new UnitOfWork(log, name, parent,
                   if (provenanceId == null) createNextProvenanceId(LOCAL, parent) else if (provenanceId.isMulticast) provenanceId.createNext(LOCAL) else provenanceId,
                   if (level == null) nextLogLevel(parent) else level,
                   defaultResult, exceptionResult, traceExecutionSources, pendingComplaintInterval.toMillis)

  private val aspectWeavingHasBeenChecked = new AtomicBoolean
  lazy val aspectCheck: Boolean = {
    import ExecutionContext.Implicits.global
    val uow = UnitOfWork("aspectCheck")
    val promise = Promise[Unit]()
    val future = uow { promise.future.map{ _ => UnitOfWork.current == uow } }
    promise.success(())
    if (Await.result(future, 100 millis)) {
      true
    } else {
      log.warn("Continuity aspect was not woven; provenance will not be carried across Future.onComplete or ActorRef.tell")
      false
    }
  }

  /** The list of class name prefixes used to exclude "uninteresting" stack frames from pending execution tracing.
   * By default it includes elements from common libraries.
   */
  var traceExemptions = List("org.uowlog.", "scala.", "akka.actor.", "akka.dispatch.", "akka.event.", "java.")
  /** Complain about pending executions that were cancelled by garbage collection.
   * This usually derives from things like a Future.onComplete for a Promise that was never completed before dropping from the reachable memory set.
   */
  def complainGC(uow: UnitOfWork, from: String) = log.warn("Garbage collected pending execution for UOW " + uow.name + " from " + from)
  /** Complain about pending executions associated with non-shared resources that were reassigned.
   * This usually derives from Akka internal SystemMessage instances that are mistakenly delivered to more than one actor.
   */
  def complainRepurpose(uow: UnitOfWork, from: String) = log.warn("Repurposed message with pending execution for UOW " + uow.name + " from " + from)
  /** Complain about a UnitOfWork that has not closed for more than its `pendingComplaintInterval`.
   * This is useful for tracking down UOWs that are being held open by forgotten pending executions.
   */
  def complainStillOpen(uow: UnitOfWork, from: Seq[String]) = ???
}

import org.uowlog.UnitOfWork._

class UnitOfWork(
  val log: Logger,
  var name: String,
  val parent: UnitOfWork,
  val provenanceId: ProvenanceId,
  val level: Level,
  val defaultResult: UOWResult,
  val exceptionResult: UOWResult,
  var traceExecutionSources: Boolean,
  val pendingComplaintInterval: Long) {

  def this(
    log: Logger,
    name: String,
    parent: UnitOfWork,
    provenanceId: ProvenanceId,
    level: Level,
    defaultResult: UOWResult,
    exceptionResult: UOWResult
  ) = this(log, name, parent, provenanceId, level, defaultResult, exceptionResult, false, 5.minutes.toMillis)

  def this(
    log: Logger,
    name: String
  ) = this(log, name, current, createNextProvenanceId(LOCAL), nextLogLevel(), NORMAL, EXCEPTION, false, 5.minutes.toMillis)

  require(log          ne null,                    "Logger may not be null")
  require(level        ne null,                     "Level may not be null")
  require(provenanceId ne null,              "ProvenanceId may not be null")
  require(provenanceId.isMulticast == false, "ProvenanceId may not be multicast")

  // Oh, the pain to get the check to happen only once, and also not blow the stack with recursion...
  if (!aspectWeavingHasBeenChecked.getAndSet(true)) aspectCheck

  if (parent != null && !provenanceId.isDescendantOf(parent.provenanceId))
    log.warn(OVERRIDE_PROVENANCE format (provenanceId, parent.provenanceId))

  val startTime = getCurrentTime
  private[uowlog] var endTime: Long = _
  private[uowlog] val atomicResult = new AtomicReference[UOWResult]
  private[uowlog] val atomicClosed = new AtomicBoolean(false)
  private[this]   val pendingFrom  = new collection.concurrent.TrieMap[Int, String]
  private[uowlog] val lastUpdate   = new AtomicLong(0)

  val values:  collection.concurrent.Map[String, String] = new collection.concurrent.TrieMap[String, String]
  val metrics: collection.concurrent.Map[String, Metric] = new collection.concurrent.TrieMap[String, Metric]

  /** Record a value associated with a key.
   * If the value was previously set to some other value, a warning message is emitted,
   * and the new value replaces the old.
   * Values are intended to be informational or act as filtering or aggregation dimensions for metrics.
   */
  def recordValue(name: String, text: String) {
    if (atomicClosed.get) log.warn(CLOSED_RECORD_VALUE format name)
    val old = values.put(name, text)
    if (old.isDefined && old.get != text) log.warn(OVERRIDE_RECORD_VALUE format name)
  }

  /** Accumulate a metric associated with a key and unit of measurement.
   * Multiple calls to addMetric for the same key will be summed in the UOW, and the associated count will be incremented.
   * Metrics may be aggregated for time series data, or used directly for instantaneous threshold alerting.
   */
  def addMetric(name: String, value: Double, units: String) { addMetric(name, value, units, 1) }

  /** Accumulate a metric associated with a key and unit of measurement.
   * Multiple calls to addMetric for the same key will be summed in the UOW, and the associated count will be increased by the indicated amount.
   * Metrics may be aggregated for time series data, or used directly for instantaneous threshold alerting.
   */
  def addMetric(name: String, value: Double, units: String, count: Int) {
    if (atomicClosed.get) log.warn(CLOSED_ADD_METRIC format name)
    val metric = metrics.getOrElseUpdate(name, new Metric(units))
    if (metric.units != units) log.warn(OVERRIDE_METRIC_UNITS format name)
    metric synchronized {
      metric.value += value
      metric.count += count
    }
  }

  /** Indicate whether the UOW has succeeded, failed due to user error, or failed due to internal problems. */
  def setResult(result: UOWResult) {
    this.atomicResult.set(result)
  }

  def getResult = atomicResult.get

  def getStartTime = Instant.ofEpochMilli(startTime).atZone(ZoneOffset.UTC)
  def getEndTime   = if (endTime > 0) Instant.ofEpochMilli(endTime).atZone(ZoneOffset.UTC) else null
  def getDuration  = if (endTime > 0) Duration.ofMillis(endTime - startTime) else null
  def getLogger    = log
  def getName      = name
  def getLevel     = level

  /** Record an exception, including message, class, and result type.
   * This will not override a result that has been explicitly set.
   * Exceptions which implement the UOWAwareException can specify which result should be set.
   */
  def recordException(exception: Throwable) {
    atomicResult.compareAndSet(null, exception match {
      case null                 => defaultResult
      case e: UOWAwareException => e.getUOWResult
      case _                    => exceptionResult
    })

    if (exception != null) {
      // cannot use recordValue here because we may already be closed
      values.putIfAbsent("message", exception.getMessage)
      values.putIfAbsent("exception", exception.getClass.getName)
    }
  }

  /** Record exceptions matching a partial function.  This is particularly useful for Future and Try recover blocks. */
  def recordException[T](pf: PartialFunction[Throwable, T]): PartialFunction[Throwable, T] = new PartialFunction[Throwable, T]{
    def apply(e: Throwable)       = { recordException(e); pf(e) }
    def isDefinedAt(e: Throwable) = pf.isDefinedAt(e)
  }

  /** Record exceptions associated with a Future.
   * This only captures exceptions that cause the Future to fail;
   * internally handled exceptions should be recorded separately (if desired).
   */
  def recordFutureFailures[T](fut: Future[T])(implicit ec: ExecutionContext): Future[T] = {
    // Done with recoverWith to ensure the returned future doesn't complete until the exception has been recorded.
    fut recoverWith recordException { case NonFatal(e) => fut }
  }

  /** Indicate if the UnitOfWork has been closed. */
  def closed = atomicClosed.get

  /** Finish the UnitOfWork with the result of a Try.
   * If the Try failed, then the contained exception is recorded in the UnitOfWork and also logged separately as an event.
   */
  def close(t: Try[_]) { t match {
    case _: Success[_] => close()
    case Failure(e)    => close(e)
  }}
  /** Finish the UnitOfWork and cause it to log its accumulated data. */
  def close() { close(null:Throwable) }
  /** Finish the UnitOfWork and cause it to log its accumulated data.
   * The provided exception is recorded in the UnitOfWork and also logged separately as an event.
   */
  def close(exception: Throwable) {
    if (atomicClosed.getAndSet(true))
      log.warn(MULTIPLE_CLOSE_WARNING format provenanceId)
    else {
      if (pendingFrom.nonEmpty)
        log.warn(PENDING_CLOSE_WARNING format provenanceId)
      endTime = getCurrentTime
      // We cannot use addMetric here because we're already closed
      val metric = metrics.getOrElseUpdate("duration", new Metric("ms"))
      if (metric.units != "ms") log.warn(OVERRIDE_METRIC_UNITS format "duration")
      metric synchronized {
        metric.value += endTime - startTime
        metric.count += 1
      }

      closeDirty(exception)
    }
  }

  private def closeDirty(exception: Throwable) {
    recordException(exception)

    def logWithMDC(logWithException: (String, Throwable) => Unit, logString: (String) => Unit) {
      MDC.put(MonitorLayout.PROVENANCE, provenanceId.toString)
      if (exception ne null) logWithException(exception.getMessage, exception)
      MDC.put(MonitorLayout.MONITOR_TYPE, "UOW")
      MDC.put(MonitorLayout.NAME, name)
      logString(this.toString)
      MDC.remove(MonitorLayout.MONITOR_TYPE)
      MDC.remove(MonitorLayout.NAME)
      MDC.remove(MonitorLayout.PROVENANCE)
    }

    level match {
      case ERROR ⇒ if (log.isErrorEnabled) { logWithMDC(log.error, log.error) }
      case WARN  ⇒ if (log.isWarnEnabled ) { logWithMDC(log.warn,  log.warn ) }
      case INFO  ⇒ if (log.isInfoEnabled ) { logWithMDC(log.info,  log.info ) }
      case DEBUG ⇒ if (log.isDebugEnabled) { logWithMDC(log.debug, log.debug) }
      case TRACE ⇒ if (log.isTraceEnabled) { logWithMDC(log.trace, log.trace) }
    }
  }

  /** Generate a fragment of the JSON log line, including the fields that are specific to being a UnitOfWork. */
  override def toString = {
    import org.uowlog.MonitorLayout.escape

    val output = new StringBuilder
    output ++= "\"name\":\"" ++= name ++= "\""
    output ++= ",\"start\":\"" ++= outputTime(startTime) ++= "\""
    if (endTime > 0) {
      output ++= ",\"end\":\"" ++= outputTime(endTime) ++= "\""
    }
    val res = atomicResult.get;
    if (res != null) {
      output ++= ",\"result\":\"" ++= res.toString += '"'
    }
    if (values.nonEmpty) {
      output ++= ",\"values\":{"
      var sep = ""
      for ((name, text) <- values) {
        if (text == null)
          output ++= sep += '"' ++= escape(name) ++= "\":null"
        else
          output ++= sep += '"' ++= escape(name) ++= "\":\"" ++= escape(text) += '"'
        sep = ","
      }
      output += '}'
    }
    if (metrics.nonEmpty) {
      output ++= ",\"metrics\":{"
      var sep = ""
      for ((name, metric) <- metrics) {
        metric synchronized {
          output ++= sep += '"' ++= escape(name) ++= "\":{\"total\":" ++= metric.value.toString ++= ",\"count\":" ++= metric.count.toString ++= ",\"units\":\"" ++= escape(metric.units) ++= "\"}"
        }
        sep = ","
      }
      output += '}'
    }
    output.toString
  }

  private def outputTime(when: Long) = Instant.ofEpochMilli(when).atZone(ZoneOffset.UTC).toString

  /** Try to figure out a meaningful source location where execution originated.
   * This skips stack frames considered uninteresting, mostly deriving from common libraries.
   * If it cannot find an interesting stack frame, then it will give the entire stack trace.
   * Since this is an expensive operation, it is gated by the `traceExecutionSources` parameter;
   * if that is false, then only "<untraced>" is returned.
   */
  def getActiveLine = {
    if (traceExecutionSources) {
      val info = new RuntimeException().getStackTrace
      val there = info.find(x => !traceExemptions.exists(x.getClassName.startsWith(_)))
      there.map(t => t.getClassName + ":" + t.getFileName + ":" + t.getLineNumber).getOrElse(
        info.map(t => t.getClassName + ":" + t.getFileName + ":" + t.getLineNumber).mkString("|")
      )
    } else {
      "<untraced>"
    }
  }

  private[this] val tokenCounter = new AtomicInteger(0)
  private[this] val debugPending = false
  /** Record that there is a pending execution for this UnitOfWork.
   * '''This should only be used if you're implementing support for carrying the UOW across async boundaries.'''
   * The UOW will not close automatically until after decrementPending is called with the token returned by this routine.
   * Care should be taken to make sure that decrementPending is eventually called, even if it is called from a finalizer;
   * otherwise the UOW will never close automatically.
   */
  def incrementPending() = {
    val token = tokenCounter.incrementAndGet()
    pendingFrom(token) = getActiveLine
    lastUpdate.set(UnitOfWork.getCurrentTime)
    token
  }
  /** Remove a pending execution from the UnitOfWork.
   * '''This should only be used if you're implementing support for carrying the UOW across async boundaries.'''
   * This releases the UOW to automatically close if no other pending executions exist.
   * This is idempotent; extra calls with the same token will be ignored.
   */
  def decrementPending(token: Int, exception: Throwable = null): Option[String] = {
    val from = pendingFrom.remove(token)
    if (from != None) {
      if (pendingFrom.isEmpty) close(exception)
      lastUpdate.set(UnitOfWork.getCurrentTime)
    }
    from
  }
  /** Give a list of all the pending executions.
   * If the `traceExecutionSources` parameter is true,
   * then the elements of the list will indicate source locations where the pending executions originated.
   */
  def getPending = pendingFrom.values

  /** Measure the provided piece of code with this UnitOfWork.
   * This UnitOfWork will become the `UnitOfWork.current` UOW within execution of the code block.
   * The UOW will close automatically after execution completes (including pending asynchonous executions, too).
   */
  def apply[T](func: => T): T = {
    var exception: Throwable = null
    val token = incrementPending()
    val prior = activeUOWs.get
    activeUOWs.set(this)
    val startTime = getCurrentTime
    try {
      func
    } catch {
      case NonFatal(e) =>
        exception = e
        throw e
    } finally {
      val endTime = getCurrentTime
      addMetric("executionTime", endTime - startTime, "ms")
      activeUOWs.set(prior)
      decrementPending(token, exception)
    }
  }

  /** Measure the provided piece of code and record the result of the Future it yields. */
  def forFuture[T](func: => Future[T])(implicit ec: ExecutionContext): Future[T] =
    apply(recordFutureFailures(func))

  /* Java interfaces */
  /** Java API: Measure the provided piece of code (which may throw an exception) with this UnitOfWork. */
  @throws[Exception] def measure   (work: CheckedWork)                { apply(work.doWork()) }

  /** Java API: Measure the provided piece of code (which returns a value and may throw an exception) with this UnitOfWork. */
  @throws[Exception] def measure[T](work: CheckedWorkWithResult[T])   = apply(work.doWork())

  /** Java API: Measure the provided piece of code with this UnitOfWork. */
                     def measure   (work: UncheckedWork)              { apply(work.doWork()) }

  /** Java API: Measure the provided piece of code (which returns a value) with this UnitOfWork. */
                     def measure[T](work: UncheckedWorkWithResult[T]) = apply(work.doWork())
}

/** Mix-in trait for exceptions that provide a customized UOWResult when recorded in a UnitOfWork. */
trait UOWAwareException {
  def getUOWResult(): UOWResult
}

/* Java interfaces */
abstract class UncheckedWork              extends UnitOfWork.Functions {                    def doWork(): Unit }
abstract class UncheckedWorkWithResult[T] extends UnitOfWork.Functions {                    def doWork(): T    }
abstract class   CheckedWork              extends UnitOfWork.Functions { @throws[Exception] def doWork(): Unit }
abstract class   CheckedWorkWithResult[T] extends UnitOfWork.Functions { @throws[Exception] def doWork(): T    }
