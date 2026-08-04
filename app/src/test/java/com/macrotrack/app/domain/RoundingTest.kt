package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RoundingTest {

    @Test
    fun matchesPythonsRoundHalfToEvenTieCase() {
        // Python: round(0.25, 1) == 0.2 -- 0.25 is an exact tie (0.25 is exactly
        // representable in binary), so it rounds to the even neighbor, 0.2.
        assertEquals(0.2, round1(0.25), 0.0)
    }

    @Test
    fun matchesPythonsFloatRepresentationQuirk() {
        // Python: round(0.15, 1) == 0.1 -- 0.15 is NOT exactly representable in
        // binary; the stored double is slightly below the true midpoint, so
        // this isn't really a tie at all once you look at the actual bits.
        assertEquals(0.1, round1(0.15), 0.0)
    }

    @Test
    fun round4MatchesAPythonFixtureValue() {
        // Python: round(-0.14445567999998943, 4) == -0.1445
        assertEquals(-0.1445, round4(-0.14445567999998943), 0.0)
    }
}
