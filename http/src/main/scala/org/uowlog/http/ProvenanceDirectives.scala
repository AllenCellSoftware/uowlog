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

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import org.slf4j.Logger
import org.uowlog._
import scala.concurrent.ExecutionContext

trait ProvenanceDirectives {
  val traceExecutionSources = false

  val extractProvenanceId = optionalHeaderValueByName("X-ProvenanceId").map(_.map(ProvenanceId.create).getOrElse(ProvenanceId.create))

  def recordRequestDetails(uow: UnitOfWork, request: HttpRequest) {
    uow.recordValue("method", request.method.value)
    uow.recordValue("scheme", request.uri.scheme)
    uow.recordValue("path", request.uri.path.toString)
  }

  def wrapRequestInUOWs(implicit log: Logger, executionContext: ExecutionContext): Directive0 =
    (extractProvenanceId & extractRequest) tflatMap { case (prov, req) =>
      val requestUOW = UnitOfWork("httpRequest", parent = null, provenanceId = prov)
      val processUOW = UnitOfWork("httpProcessing", parent = requestUOW, traceExecutionSources = traceExecutionSources)

      def copyValues() {
        for {
          (key, value) <- processUOW.values
          if !(requestUOW.values contains key)
        } requestUOW.recordValue(key, value)
      }

      recordRequestDetails(requestUOW, req)
      (mapRouteResult {
        case Complete(response) =>
          requestUOW.recordValue("status", response.status.intValue.toString)
          response.status match {
            case _: ClientError => requestUOW.setResult(UOWResult.FAILURE)
            case _: ServerError => requestUOW.setResult(UOWResult.EXCEPTION)
            case _              => requestUOW.setResult(UOWResult.NORMAL)
          }
          val watcher = Flow[ByteString].watchTermination(){ (other, future) => future.onComplete { x => copyValues(); requestUOW.close(x) }; other }
          Complete(response.mapEntity(_.transformDataBytes(watcher)))
        case x =>
          requestUOW.setResult(UOWResult.EXCEPTION)
          copyValues()
          requestUOW.close
          x
      }) & (mapInnerRoute { inner => ctx =>
        processUOW {
          recordRequestDetails(UnitOfWork.current, req)
          inner(ctx)
        }
      })
    }
}
