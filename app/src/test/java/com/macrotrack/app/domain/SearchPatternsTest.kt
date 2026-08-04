package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPatternsTest {
    @Test
    fun wrapsTheQueryWithWildcardsAndLowercases() {
        assertEquals("%chicken breast%", SearchPatterns.ilikePattern("Chicken Breast"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("%oats%", SearchPatterns.ilikePattern("  oats  "))
    }

    @Test
    fun escapesLiteralPercentAndUnderscoreInUserInput() {
        assertEquals("%100\\% whole wheat%", SearchPatterns.ilikePattern("100% whole wheat"))
        assertEquals("%greek\\_yogurt%", SearchPatterns.ilikePattern("greek_yogurt"))
    }
}
