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

import akka.actor._
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout

import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FunSpecLike, Inspectors, Matchers}

import scala.collection.JavaConversions._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.Try

import spray.json._

class UnitOfWorkSpec extends TestKit(ActorSystem()) with FunSpecLike with Matchers with Inspectors with Eventually with PatienceConfiguration with LogFileCapture with EventMatchers with UOWMatchers {
  import DefaultJsonProtocol._

  override def afterAll() {
    super.afterAll()
    system.terminate()
  }

  implicit override val patienceConfig = new PatienceConfig(timeout = scaled(Span(2, Seconds)), interval = scaled(Span(15, Millis)))

  describe("AspectCheck") {
    it("should indicate if aspects have been woven") {
      UnitOfWork.aspectCheck shouldBe true
    }
  }

  describe("UnitOfWork") {
    it("should log UOW start and end times in proper format") {
try {
      val uow = UnitOfWork("Foo")
      uow.close()
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have (name ("Foo"), logType ("UOW"))
          entry.get[String]("start") should fullyMatch regex ("""\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d\.\d\d\dZ""")
          entry.get[String]("end"  ) should fullyMatch regex ("""\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d\.\d\d\dZ""")
        }
      }
} catch { case e : Throwable => printLogMessages(); throw e; }
    }

    it("should log exceptions with with proper provenance id") {
      val uow = UnitOfWork("Gleep")
      Try { uow { throw new RuntimeException("Whee!") } }
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have (logType ("EVENT"), provenance (uow.provenanceId))
          val ex = entry.get[List[JsObject]]("exceptions")
          ex(0).fields("message").convertTo[String] should include ("Whee!")
        }
      }
    }

    it("should log exceptions without the raw stack trace") {
      val uow = UnitOfWork("Gleep")
      Try { uow { throw new RuntimeException("Whee!") } }
      eventually {
        forAtLeast(1, logFile.messages) { entry => entry should have (logType ("EVENT")) }
        forAll (logFile.messages) { entry => entry.raw should startWith ("{") }
        forAll (logFile.messages) { entry => entry.raw should not include regex ("""\n.*\n""".r) }
      }
    }
  }

  describe("NestedUnitOfWork") {
    it("should make UOWs with derived provenance") {
      val parent = UnitOfWork("foo", parent = null)
      parent {
        val child = UnitOfWork("bar")
        child.provenanceId.toString should equal (parent.provenanceId.toString + "1")
        child {
          val grandchild = UnitOfWork("baz")
          grandchild.provenanceId.toString should equal (child.provenanceId.toString + ",1")
        }
      }
    }
  }

  def hookToNever() {
    import ExecutionContext.Implicits.global
    val promise = Promise[Unit]()
    promise.future.onComplete(_ => log.info("never happened!"))
  }

  describe("propagation through Futures") {
    it("should hold a UOW open for Future apply") {
      import ExecutionContext.Implicits.global
      val promise = Promise[Unit]()
      val uow = UnitOfWork("box")
      uow {
        Future {
          Await.result(promise.future, 10 seconds)
          log.info("ARGH!")
        }
      }
      Thread.sleep(100)
      forAll(logFile.messages) { _ should not (have message "ARGH!") }
      uow should not be ('closed)
      promise.success(())
      eventually {
        forAtLeast(1, logFile.messages) { _ should have message "ARGH!" }
        uow shouldBe 'closed
      }
    }
    it("should hold a UOW open for Future onComplete handlers") {
      import ExecutionContext.Implicits.global
      val promise = Promise[Unit]()
      val uow = UnitOfWork("box")
      uow {
        promise.future.onComplete(_ => log.info("ARGH!"))
      }
      Thread.sleep(100)
      forAll(logFile.messages) { _ should not (have message "ARGH!") }
      uow should not be ('closed)
      promise.success(())
      eventually {
        forAtLeast(1, logFile.messages) { _ should have message "ARGH!" }
        uow shouldBe 'closed
      }
    }
    it("should eventually close UOW with executions pending on incomplete Futures that get garbage collected") {
      val uow = UnitOfWork("box")
      uow { hookToNever() }
      uow should not be ('closed)
      eventually {
        System.gc()
        uow shouldBe 'closed
        forAtLeast(1, logFile.messages) { _ should have (name ("box")) }
        forAll(logFile.messages) { _ should not (have message "never happened!") }
      }
    }
  }

  case class Negligible(num: Int) extends DeadLetterSuppression

  describe("propagation through Actor messaging") {
    it("should hold a UOW open for actor tells") {
      val gate = Promise[Unit]()
      val sleeper = system.actorOf(Props(new Actor { def receive = {
        case "snooze" =>
          Await.ready(gate.future, 100 millis)
          log.info("yawn in " + UnitOfWork.current.provenanceId)
        case msg =>
          log.info("bogus: " + msg)
      }}))
      try {
        val uow = UnitOfWork("tellBox")
        uow { sleeper ! "snooze" }
        uow should not be ('closed)
        gate.success(())
        eventually {
          uow shouldBe 'closed
          forAtLeast(1, logFile.messages) { _ should have message ("yawn in " + uow.provenanceId) }
          forAll(logFile.messages) { e => Try(e.get[String]("message")).getOrElse("") should not startWith ("bogus") }
        }
      } finally { system stop sleeper }
    }

    it("should release the UOW even if the message isn't handled") {
      val messages = scala.collection.mutable.Queue[Any]()
      val recorder = system.actorOf(Props(new Actor {
        override def postStop() {
          system.eventStream.unsubscribe(self)
          super.postStop()
        }
        def receive = { case msg => messages += msg }
      }))
      system.eventStream.subscribe(recorder, classOf[UnhandledMessage])

      val deaf = system.actorOf(Props(new Actor { def receive = {
        case "snooze" => log.info("yawn in " + UnitOfWork.current.provenanceId)
      }}))
      try {
        val uow = UnitOfWork("tellBox")
        uow { deaf ! "bogus" }
        eventually {
          uow shouldBe 'closed
          forAtLeast(1, messages) { case m: UnhandledMessage => m.message shouldBe "bogus"; m.recipient shouldBe deaf }
        }
      } finally { system stop deaf; system stop recorder }
    }

    it("should release the UOW if a receiving actor is already dead") {
      val messages = scala.collection.mutable.Queue[Any]()
      val recorder = system.actorOf(Props(new Actor {
        override def postStop() {
          system.eventStream.unsubscribe(self)
          super.postStop()
        }
        def receive = { case msg => messages += msg }
      }))
      system.eventStream.subscribe(recorder, classOf[AllDeadLetters])

      val gate = Promise[Unit]()
      val kaput = system.actorOf(Props(new Actor {
        override def postStop() {
          gate.success(())
          super.postStop()
        }
        def receive = {
          case "snooze" => log.info("yawn in " + UnitOfWork.current.provenanceId)
        }
      }))
      system stop kaput
      Await.ready(gate.future, 2 seconds)
      try {
        val uow = UnitOfWork("tellBox")
        uow { kaput ! Negligible(0) }
        eventually {
          uow shouldBe 'closed
          forAtLeast(1, messages) { case m: SuppressedDeadLetter => m.message shouldBe Negligible(0); m.recipient should ((equal (kaput)) or (equal (system.deadLetters))) }
        }
      } finally { system stop recorder }
    }

    it("should release the UOW if a receiving actor dies after message sent") {
      val messages = scala.collection.mutable.Queue[Any]()
      val recorder = system.actorOf(Props(new Actor {
        override def postStop() {
          system.eventStream.unsubscribe(self)
          super.postStop()
        }
        def receive = { case msg => messages += msg }
      }))
      system.eventStream.subscribe(recorder, classOf[AllDeadLetters])

      val gate = Promise[Unit]()
      val kaput = system.actorOf(Props(new Actor { def receive = {
        case "snooze" => Thread.sleep(1000); gate.success(())
      }}))
      try {
        kaput ! "snooze"
        val uow = UnitOfWork("tellBox")
        uow { kaput ! Negligible(1) }
        gate should not be ('completed)
        system stop kaput
        eventually {
          uow shouldBe 'closed
          forAtLeast(1, messages) { case m: SuppressedDeadLetter => m.message shouldBe Negligible(1); m.recipient should ((equal (kaput)) or (equal (system.deadLetters))) }
        }
      } finally { system stop recorder }
    }

    it("should not screw up AutoReceiveMessages") {
      val quiet = system.actorOf(Props(new Actor { def receive = { case "" => () } }))
      try {
        val uow = UnitOfWork("echo chamber")
        uow { implicit val from = testActor; quiet ! Identify("hello") }
        expectMsg(ActorIdentity("hello", Some(quiet)))
      } finally { system stop quiet }
    }

    it("should not screw up the AskPattern") {
      val greeter = system.actorOf(Props(new Actor { def receive = { case "hello" => sender ! "goodbye" } }))
      try {
        val uow = UnitOfWork("asking")
        implicit val timeout = Timeout(2 seconds)
        val response = uow { greeter ? "hello" }
      } finally { system stop greeter }
    }
  }

  describe("propagation through Actor lifecycle") {
    it("should transmit UOW with actor creation") {
      var recorded: UnitOfWork = null
      class WhereAmI extends Actor {
        override def preStart() {
          recorded = UnitOfWork.current
        }
        def receive = { case _ => context stop self }
      }
      val uow = UnitOfWork("InTheBeginning")
      val actor = uow {
        system.actorOf(Props(new WhereAmI))
      }
      try {
        eventually {
          recorded shouldBe theSameInstanceAs (uow)
          uow shouldBe 'closed
        }
      } finally { system stop actor }
    }
  }

  describe("UnitOfWork.Functions") {
    import UnitOfWork.functions._

    it("should support withUnitOfWork syntax") {
      var prov: ProvenanceId = null
      Try { withUnitOfWork("Gleep") { prov = UnitOfWork.current.provenanceId; throw new RuntimeException("Whee!") } }
      eventually {
        forAtLeast(1, logFile.messages) { entry =>
          entry should have (logType ("EVENT"), provenance (prov))
          val ex = entry.get[List[JsObject]]("exceptions")
          ex(0).fields("message").convertTo[String] should include ("Whee!")
        }
      }
    }
  }
}
