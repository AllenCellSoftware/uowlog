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

import org.scalatest.{FunSpecLike, Matchers}

class NaturalOrderingSpec extends FunSpecLike with Matchers {
  import org.uowlog.NaturalOrdering.compare

  describe("NaturalOrdering") {
    it("should ignore leading whitespace") {
      compare("  a", "b") should be < (0)
      compare("  b", "a") should be > (0)
      compare("  a", "a") should equal(0)
    }
    it("should ignore trailing whitespace") {
      compare("a  ", "a") should equal(0)
    }
    it("should treat multiple whitespace the same") {
      compare("a  b", "a b") should equal(0)
    }
    it("should count whitespace as a word separator") {
      compare("a b", "ab") should be < (0)
    }

    it("should sort embedded numbers in numeric order") {
      compare("a2", "a100") should be < (0)
      compare("a200", "a100") should be > (0)
      compare("a080", "a100") should be < (0)
      compare("a200", "a200") should equal(0)
    }
    it("should ignore leading zeros for unequal numbers") {
      compare("a002", "a3") should be < (0)
      compare("a002", "a1") should be > (0)
    }
    it("should count leading zeros for equal numbers") {
      compare("a2", "a002") should be < (0)
      compare("a002", "a2") should be > (0)
    }
    it("should sort text following embedded numbers") {
      compare("a20b", "a20c") should be < (0)
      compare("a20c", "a20b") should be > (0)
    }
    it("should sort text following embedded numbers with leading zeroes") {
      compare("a0020b", "a20c") should be < (0)
      compare("a0020c", "a20b") should be > (0)
    }
    it("should not blow the stack for huge numbers") {
      val s = "1" * 100000
      try {
        compare(s, s) should equal(0)
      } catch {
        case e: StackOverflowError ⇒ fail("stack overflow")
      }
    }
    it("should compare huge numbers properly") {
      val s = "0" * 100000
      try {
        compare("2" + s, "10" + s) should be < (0)
        compare("10" + s, "2" + s) should be > (0)
      } catch {
        case e: StackOverflowError ⇒ fail("stack overflow")
      }
    }
    it("should not blow the stack for huge mixtures of numbers and letters") {
      val s = "s1" * 100000
      try {
        compare(s, s) should equal(0)
      } catch {
        case e: StackOverflowError ⇒ fail("stack overflow")
      }
    }
    it("should handle text after a set of zeroes") {
      compare("00a", "a") should be > (0)
      compare("0a", "0a") should equal(0)
      compare("a", "00a") should be < (0)
    }

    it("should handle a complex case properly") {
      compare("..f5054477-35a1-4787-afb3-f4b6afc0e9fd:", "..f5054477-35a1-4787-afb3-f4b6afc0e9fd:") should equal(0)
      compare("..f5054477-35a1-4787-afb3-f4b6afc0e9fd:1", "..f5054477-35a1-4787-afb3-f4b6afc0e9fd:2") should be < (0)
      compare("..f5054477-35a1-4787-afb3-f4b6afc0e9fd:1r", "..f5054477-35a1-4787-afb3-f4b6afc0e9fd:2r") should be < (0)
    }

    it("should obey the ordering demo from Martin Pool & Pierre-Luc Paour") {
      val orig = List(
        "1-2", "1-02", "1-20", "10-20", "fred", "jane",
        "p ic3", "pic 4 else", "pic 5", "pic 5", "pic 5 something", "pic 6", "pic   7", "pic 8",
        "pic01", "pic2", "pic02", "pic02a", "pic3", "pic4", "pic05",
        "pic100", "pic100a", "pic120", "pic121",
        "pic02000", "tom", "x2-g8", "x2-y7", "x2-y08", "x8-y8"
      )

      val scrambled = util.Random.shuffle(orig)
      val sorted = scrambled.sorted(NaturalOrdering)
      scrambled should not be orig
      sorted shouldBe orig
    }
  }
}
