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

import scala.annotation.tailrec
import scala.util.control.NonFatal

trait AvailablePort {
  def port = _port
  private lazy val _port: Int = {
    @tailrec def findUnusedPort: Int = {
      import scala.util.Random
      import java.net._

      val num = Random.nextInt(20000) + 10000
      (try {
        new Socket("0.0.0.0", num).close()
        None
      } catch {
        case _: ConnectException => Some(num)
        case NonFatal(e) => None
      }) match {
        case Some(n) => n
        // Have to pull this out of the try/catch for tail recursion to be optimized
        case _ => findUnusedPort
      }
    }

    findUnusedPort
  }
}
