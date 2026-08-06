package com.macroplus.app.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rounds like Python's round(x, n): round-half-to-even on the double's exact
 * binary value. BigDecimal(value) (NOT BigDecimal.valueOf, which goes through
 * Double.toString() first) captures that exact binary value, so this
 * reproduces Python's float-rounding quirks too -- e.g. round(2.675, 2) ==
 * 2.67 in both languages, because 2.675 is actually stored as
 * 2.67499999999999982236431605997495353221893310546875 and isn't a real tie.
 */
internal fun round1(value: Double): Double = BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toDouble()

internal fun round4(value: Double): Double = BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN).toDouble()
