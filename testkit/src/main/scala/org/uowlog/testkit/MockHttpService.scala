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

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.StatusCodes.UnprocessableEntity
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling._
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{BidiFlow, Keep}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.Assertions
import org.uowlog._
import org.uowlog.http._
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

/** Mixin trait for testing an HttpClient with a fake service. */
trait MockHttpService extends HttpClient with AvailablePort {
  /** A set of routes to handle the expected requests.
   * Each route will be used once, in sequence;
   * the first request will go to the first route,
   * the second request to the second route, etc.
   * If there are more requests than routes, then the excess requests will be rejected.
   * Any route rejections (for whatever reasons) will be transmitted to the client and turned into test failures.
   */
  def expected: Seq[Route]

  import MockHttpService._
  import java.util.concurrent.ConcurrentLinkedQueue
  import system.dispatcher

  override def host = "localhost"
  override def port = super[AvailablePort].port
  /** Augment the flow with server rejection recognition. */
  override def flowWithUOW[T](uowName: String) =
    BidiFlow.fromFunctions(
      (pair: (HttpRequest,T)) => (pair._1, pair),
      (pair: (Try[HttpResponse],(HttpRequest,T))) => pair match {
        case (Success(response), (request, context)) =>
          (Try {
            if (response.status == UnprocessableEntity && response.headers.exists(_.name == "X-TestFailureWithRejections")) {
              val rejections = Await.result(Unmarshal(response.entity).to[Seq[Rejection]], 1 second)
              Assertions.fail(rejections.map("  " + _.toString).mkString("Unexpected request to mock server:\n" + request + "\n", "\n", ""))
            }
            response
          }, context)
        case (result, (request, context)) =>
          (result, context)
      }
    ).joinMat(super.flowWithUOW(uowName))(Keep.right)

  private val _actual = new ConcurrentLinkedQueue[(HttpRequest, Future[RouteResult])]()
  /** The set of actual request / response pairs.
   * This can be inspected after the requests are run.
   * One common use is to give more detail on failures.
   */
  def actual = _actual.toList

  // mechanism to indicate when to stop
  private val stopPromise = Promise[Done]()
  private val stoppedPromise = Promise[Done]()
  def startMockService() = {
    val remaining = new ConcurrentLinkedQueue(expected: java.util.Collection[Route])
    val progression: Route = (rc: RequestContext) => {
      val rf = remaining.poll match {
        case null => Future.successful(RouteResult.Rejected(List(MockHttpService.TooManyRequests)))
        case route => route(rc)
      }
      _actual.add(rc.request -> rf)
      rf
    }
    val wrapped = handleRejections(encodeRejections)(progression)
    val bindingFuture = Http().bindAndHandle(wrapped, "0.0.0.0", port)
    stoppedPromise.completeWith(stopPromise.future.flatMap(_ => bindingFuture).flatMap(_.unbind()).map(_ => Done))
    bindingFuture.map(_ => Done)
  }
  def stopMockService() = {
    stopPromise.success(Done)
    stoppedPromise.future
  }

  def runWithMockService[R](body: => R): R = {
    _actual.clear()
    startMockService()
    try { body } finally { stopMockService() }
  }
}

object MockHttpService {
  case object TooManyRequests extends akka.http.javadsl.server.CustomRejection with Rejection

  class UnserializableRejection(val wrappedName: String) extends Serializable {
    def this(r: AnyRef) = this(r.getClass.getName)
    override def toString() = "Unserializable rejection class " + wrappedName
  }

  implicit val marshalSeqRejections: ToEntityMarshaller[Seq[Rejection]] = Marshaller.opaque{ (rejections: Seq[Rejection]) =>
    // Ugh... java style io
    import java.io._
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeInt(rejections.length)
    rejections.foreach(_ match {
      case s: Serializable => oos.writeObject(s)
      case u               => oos.writeObject(new UnserializableRejection(u))
    })
    oos.flush()
    HttpEntity(baos.toByteArray)
  }

  implicit val unmarshalSeqRejections: FromEntityUnmarshaller[Seq[Rejection]] = Unmarshaller.byteArrayUnmarshaller map { bytes =>
    import java.io._
    val ois = new ObjectInputStream(new ByteArrayInputStream(bytes))
    val num = ois.readInt
    for (_ <- 1 to num) yield ois.readObject().asInstanceOf[Rejection]
  }

  val encodeRejections = RejectionHandler.newBuilder.handleAll[Rejection](rejections =>
    complete((UnprocessableEntity, List(RawHeader("X-TestFailureWithRejections", "blork!")), rejections))
  ).result
}

