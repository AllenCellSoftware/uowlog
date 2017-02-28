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

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.unmarshalling.Unmarshaller._
import akka.stream.Materializer
import akka.stream.scaladsl.{BidiFlow, Flow, Source, Sink}
import akka.util.ByteString
import com.typesafe.config.Config
import org.uowlog._
import org.uowlog.ProvenanceClass._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

class HttpError(val status: StatusCode, val message: String) extends RuntimeException(message) {
  def this(status: StatusCode) = this(status, status.toString)
  def this(actual: StatusCode, expected: StatusCode) = this(actual, s"Expected status ${expected.intValue} but got status ${actual.intValue}: $actual")
}

abstract class HttpClient(val config: Config)(implicit val system: ActorSystem, val materializer: Materializer) extends UOWLogging {
  def this()(implicit system: ActorSystem, materializer: Materializer) = this(system.settings.config)

  import system.dispatcher

  val serviceName: String

  def host = config.getString(s"$serviceName.http.host")
  def port = config.getInt   (s"$serviceName.http.port")
  def settings = ConnectionPoolSettings(if (config.hasPath(s"$serviceName.http")) config.getConfig(s"$serviceName.http").withFallback(config) else config)

  def withContext[I,O,T](f: I => O): ((I, T)) => (O, T) = { case (i, t) => f(i) -> t }
  def addProvenanceFlow[T] = Flow.fromFunction(withContext[HttpRequest, HttpRequest, T](addHeader("X-Provenance-Id", UnitOfWork.createNextProvenanceId(REMOTE).toString)))
  def flow[T] = addProvenanceFlow[T].via(Http().cachedHostConnectionPool[T](host, port, settings))
  def wrapHttpInUOW[T](uowName: String): BidiFlow[
    (Try[HttpResponse], (UnitOfWork, T)), (Try[HttpResponse], T),
    (HttpRequest, T), (HttpRequest, (UnitOfWork, T)),
    NotUsed] =
    BidiFlow.fromFunctions(
      (pair: (Try[HttpResponse],(UnitOfWork,T))) => pair match {
        case (Success(response), (uow, context)) =>
          uow.recordValue("status", response.status.intValue.toString)

          // Measure the time until the response is fully consumed
          val bodyConsumed = Promise[Done]
          val watcherFlow = Flow[ByteString].watchTermination(){ (other, future) => bodyConsumed.completeWith(future); other }
          val measuredResponse = response.mapEntity(_.transformDataBytes(watcherFlow))

          // By entering the UOW and hooking to the future,
          // we also capture the case when the body is garbage-collected before being consumed
          uow { bodyConsumed.future.failed.foreach { uow.recordException(_) } }

          (Success(measuredResponse), context)
        case (result @ Failure(e), (uow, context)) =>
          uow.close(e)
          (result, context)
      },
      (pair: (HttpRequest,T)) => (pair._1, (UnitOfWork(uowName), pair._2))
    )
  def flowWithUOW[T](uowName: String) = flow[(UnitOfWork,T)].join(wrapHttpInUOW[T](uowName))

  def sendRequest(uowName: String, request: HttpRequest): Future[HttpResponse] = {
    val promise = Promise[HttpResponse]
    Source.single(request -> promise)
      .via(flowWithUOW(uowName)).map{ case (result, prom) => prom complete result }
      .to(Sink.head)
      .run()
    promise.future
  }
}
