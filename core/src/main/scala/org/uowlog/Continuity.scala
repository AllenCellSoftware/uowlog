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

import org.uowlog.ProvenanceClass._

private[uowlog] class UOWFutureApplyWrapper[T](func: () => T) extends (() => T) {
  val uow = UnitOfWork.current
  val token = uow.incrementPending()

  def apply(): T = {
    uow {
      uow.decrementPending(token)
      func()
    }
  }

  override def finalize() {
    uow.decrementPending(token) foreach { from => UnitOfWork.complainGC(uow, from) }
  }
}

private[uowlog] class UOWFutureOnCompleteWrapper[-T, +U](func: T => U) extends (T => U) {
  val uow = UnitOfWork.current
  val token = uow.incrementPending()

  def apply(arg: T): U = {
    uow {
      uow.decrementPending(token)
      func(arg)
    }
  }

  override def finalize() {
    uow.decrementPending(token) foreach { from => UnitOfWork.complainGC(uow, from) }
  }
}

private[uowlog] abstract sealed class UOWActorMessageWrapper {
  def contents(): Any
}

private[uowlog] case class UOWLocalActorMessageWrapper(contents: Any) extends UOWActorMessageWrapper {
  val uow = UnitOfWork.current
  val token = uow.incrementPending()
  // Don't need a finalize here because the message will be handled somehow... even if that means becoming a DeadLetter.
}
private[uowlog] case class UOWRemoteActorMessageWrapper(contents: Any) extends UOWActorMessageWrapper {
  val level = UnitOfWork.nextLogLevel()
  val provenance = UnitOfWork.current.provenanceId.createNext(REMOTE)
}

