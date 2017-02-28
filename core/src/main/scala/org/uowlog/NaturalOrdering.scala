package org.uowlog

/*
 NaturalOrdering.scala -- Perform 'natural order' comparisons of strings in Scala.
 Copyright (C) 2013 by T. Alexander Popiel <apopiel@marchex.com>

 Inspired by the Java version by Pierre-Luc Paour,
 but with heavy modifications both in style and in the specifics of the ordering,
 particularly relating to the treatment of interstitial whitespace and zeroes.

 Copyright (C) 2003 by Pierre-Luc Paour <natorder@paour.com>

 Based on the C version by Martin Pool,
 Copyright (C) 2000 by Martin Pool <mbp@humbug.org.au>

 This software is provided 'as-is', without any express or implied
 warranty.  In no event will the authors be held liable for any damages
 arising from the use of this software.

 Permission is granted to anyone to use this software for any purpose,
 including commercial applications, and to alter it and redistribute it
 freely, subject to the following restrictions:

 1. The origin of this software must not be misrepresented; you must not
 claim that you wrote the original software. If you use this software
 in a product, an acknowledgment in the product documentation would be
 appreciated but is not required.
 2. Altered source versions must be plainly marked as such, and must not be
 misrepresented as being the original software.
 3. This notice may not be removed or altered from any source distribution.
*/

import scala.annotation.tailrec

/** Provide an Ordering based on the Natural Sort algorithm by Martin Pool.
 * @see [[https://github.com/sourcefrog/natsort]]
 */
trait NaturalOrdering[T] extends Ordering[T] {
  def compare(o1: T, o2: T): Int = NaturalOrdering.compareStrings(o1.toString, o2.toString)
}

object NaturalOrdering extends NaturalOrdering[Any] {
  @inline private final def charAt(s: String, p: Int) = if (s.length > p) s.charAt(p) else 0

  private final def compareStrings(a: String, b: String): Int = {
    @inline @tailrec def compareDigits(ia: Int, ib: Int): Int = {
      val ca = charAt(a, ia)
      val cb = charAt(b, ib)
      // Ideally, this would just call compareText(ia, ib),
      // but mutual tail recursion isn't properly optimized
      if (!Character.isDigit(ca) && !Character.isDigit(cb)) 0
      else if (!Character.isDigit(ca)) -1
      else if (!Character.isDigit(cb)) +1
      else if (ca < cb) compareDigitsLess(ia + 1, ib + 1)
      else if (ca > cb) compareDigitsMore(ia + 1, ib + 1)
      else compareDigits(ia + 1, ib + 1)
    }
    @inline @tailrec def compareDigitsLess(ia: Int, ib: Int): Int = {
      val ca = charAt(a, ia)
      val cb = charAt(b, ib)
      if (!Character.isDigit(ca)) -1
      else if (!Character.isDigit(cb)) +1
      else compareDigitsLess(ia + 1, ib + 1)
    }
    @inline @tailrec def compareDigitsMore(ia: Int, ib: Int): Int = {
      val ca = charAt(a, ia)
      val cb = charAt(b, ib)
      if (!Character.isDigit(cb)) +1
      else if (!Character.isDigit(ca)) -1
      else compareDigitsMore(ia + 1, ib + 1)
    }

    @inline @tailrec def compareText(sa: Int, sb: Int, sza: Int, szb: Int): Int = {
      var ia = sa
      var ib = sb
      var ca = charAt(a, ia)
      var cb = charAt(b, ib)
      var nza = sza
      var nzb = szb
      while (Character.isSpaceChar(ca)) { nza = 0; ia += 1; ca = charAt(a, ia) }
      while (Character.isSpaceChar(cb)) { nzb = 0; ib += 1; cb = charAt(b, ib) }
      while (ca == cb && ca != 0 && !Character.isDigit(ca) && !Character.isSpaceChar(ca)) {
        ia += 1; ib += 1
        ca = charAt(a, ia)
        cb = charAt(b, ib)
      }
      while (ca == '0') { nza += 1; ia += 1; ca = charAt(a, ia) }
      while (cb == '0') { nzb += 1; ib += 1; cb = charAt(b, ib) }
      if (ca == 0 && cb == 0) nza - nzb
      else if (Character.isDigit(ca) && Character.isDigit(cb)) {
        // Ideally, this would just return the result of compareDigits,
        // but mutual tail recursion isn't properly optimized
        val result = compareDigits(ia, ib)
        if (result != 0)
          result
        else {
          while (Character.isDigit(ca)) { ia += 1; ca = charAt(a, ia) }
          while (Character.isDigit(cb)) { ib += 1; cb = charAt(b, ib) }
          compareText(ia, ib, nza, nzb)
        }
      } else if ((ca == 0 || Character.isSpaceChar(ca)) && (cb == 0 || Character.isSpaceChar(cb))) compareText(ia, ib, nza, nzb)
      else if (ca < cb) -1
      else if (ca > cb) +1
      else compareText(ia, ib, nza, nzb)
    }

    compareText(0, 0, 0, 0)
  }
}
