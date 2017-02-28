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

import org.scalatest.{FunSpec, Matchers}
import org.scalatest.Inspectors._
import org.uowlog.ProvenanceClass._
import scala.util.Random

class ProvenanceIdSpec extends FunSpec with Matchers {
  describe("ProvenanceId comparison") {
    it("should be equal for the same string representation") {
      ProvenanceId.create("Foo.Bar.baz:1,2,3") should equal(ProvenanceId.create("Foo.Bar.baz:1,2,3"))
    }
    it("should be unequal for different string representations") {
      ProvenanceId.create("Foo.Bar.baz:1,2,3") should not equal (ProvenanceId.create("Foo.Bar.qux:"))
    }
    it("should sort child ids in order of generation") {
      val base = ProvenanceId.create
      val children = (1 to 1000).map(_ ⇒ base.createNext(if (Random.nextBoolean) LOCAL else REMOTE))
      for {
        j ← 0 to 98
        k ← j + 1 to 99
      } {
        children(j) should be < (children(k))
        children(k) should be > (children(j))
      }
    }
  }

  describe("ProvenanceId generation") {
    it("should be thread-safe for base provenance") {
      val nThreads = 8
      val idsToGenerate = 10000

      import java.util.concurrent._
      val service = Executors.newFixedThreadPool(nThreads)
      val collectors = (1 to nThreads) map { _ ⇒
        val collector = new Runnable {
          var ids: Set[ProvenanceId] = Set.empty[ProvenanceId]
          def run {
            ids = ((1 to idsToGenerate) map { _ ⇒ ProvenanceId.create }).toSet
          }
        }
        service.execute(collector)
        collector
      }
      service.shutdown
      service.awaitTermination(10, TimeUnit.SECONDS)

      collectors.map(_.ids).flatten.toSet.size should equal(nThreads * idsToGenerate)
    }

    it("should be thread-safe for child provenance") {
      val nThreads = 8
      val idsToGenerate = 10000
      val base = ProvenanceId.create

      import java.util.concurrent._
      val service = Executors.newFixedThreadPool(nThreads)
      val collectors = (1 to nThreads) map { _ ⇒
        val collector = new Runnable {
          var ids: Set[ProvenanceId] = Set.empty[ProvenanceId]
          def run {
            ids = (1 to idsToGenerate) map { _ ⇒ base.createNext(LOCAL) } toSet
          }
        }
        service.execute(collector)
        collector
      }
      service.shutdown
      service.awaitTermination(10, TimeUnit.SECONDS)

      collectors.map(_.ids).flatten.toSet.size should equal(nThreads * idsToGenerate)

      // prove that there was interleaving of the generated ids
      forAtLeast(2, collectors) { c1 =>
        forAtLeast(1, collectors) { c2 =>
          c1 should not be (c2)
          c1.ids.min shouldBe < (c2.ids.max)
          c1.ids.max shouldBe > (c2.ids.min)
        }
      }
    }

    it("should start child ids at 1, then increment by 1 each time") {
      val base = ProvenanceId.create
      for (j ← 1 to 10) {
        base.createNext(LOCAL).toString should equal(base.toString + j)
      }
    }

    it("should insert separator commas after the first generation of children") {
      val base = ProvenanceId.create
      val child = base.createNext(LOCAL)
      val grand1 = child.createNext(LOCAL)
      val grand2 = child.createNext(LOCAL)
      val great11 = grand1.createNext(LOCAL)
      val great21 = grand2.createNext(LOCAL)

      grand1.toString should equal(child.toString + ",1")
      grand2.toString should equal(child.toString + ",2")
      great11.toString should equal(grand1.toString + ",1")
      great21.toString should equal(grand2.toString + ",1")
    }

    it("should add an 'r' to remote ids") {
      val base = ProvenanceId.create
      val child = base.createNext(REMOTE)
      val grand = child.createNext(REMOTE)

      child.toString should equal(base.toString + "1r")
      grand.toString should equal(child.toString + ",1r")
    }

    it("should add an 'm' to multicast ids") {
      val base = ProvenanceId.create
      val child = base.createNext(MULTICAST)
      val grand = child.createNext(MULTICAST)

      child.toString should equal(base.toString + "1m")
      grand.toString should not (equal(child.toString + ",1m"))
      grand.toString should fullyMatch regex (child.toString + """,\d+m""")
    }

    it("should work correctly for reconstituted provenance ids") {
      val base = ProvenanceId.create
      val rebuilt1 = ProvenanceId.create(base.toString)
      val child = rebuilt1.createNext(LOCAL)
      val rebuilt2 = ProvenanceId.create(child.toString)
      val grand = rebuilt2.createNext(LOCAL)

      child.toString should equal(base.toString + "1")
      grand.toString should equal(base.toString + "1,1")
    }
  }

  describe("ProvenanceIds") {
    it("should end with a ':' for top level ids") {
      ProvenanceId.create.toString should endWith(":")
    }

    it("should correctly identify descendants") {
      val outerId = ProvenanceId.create
      val nestedId = outerId.createNext(LOCAL)
      val nestedRemoteId = outerId.createNext(REMOTE)

      val unrelatedId = ProvenanceId.create

      outerId.isDescendantOf(unrelatedId) should equal(false)

      nestedId.isDescendantOf(outerId) should equal(true)
      nestedRemoteId.isDescendantOf(outerId) should equal(true)

      nestedId.isDescendantOf(unrelatedId) should equal(false)
      nestedRemoteId.isDescendantOf(unrelatedId) should equal(false)

      nestedId.isDescendantOf(nestedRemoteId) should equal(false)
      nestedRemoteId.isDescendantOf(nestedId) should equal(false)
    }

    it("should be able to report root provenance") {
      val deep = ProvenanceId.create("foo.bar.baz:1,2,1,4r,1")
      deep.isDescendantOf(deep.root) shouldBe true
      deep.root shouldBe 'topLevel
    }

    it("should not create extra ProvenanceId instances when giving root id on a top level id") {
      val top = ProvenanceId.create("foo.bar.baz:")
      top.root shouldBe theSameInstanceAs (top)
    }
  }
}
