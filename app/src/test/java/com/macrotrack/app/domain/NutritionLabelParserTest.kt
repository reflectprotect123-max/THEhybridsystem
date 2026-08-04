package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelParserTest {

    @Test
    fun parsesACleanFourRowLabel() {
        val lines = listOf(
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("124Cal (520kJ)", left = 200, top = 100, right = 320, bottom = 120),
            OcrLine("Protein", left = 0, top = 130, right = 80, bottom = 150),
            OcrLine("3.2g", left = 200, top = 130, right = 250, bottom = 150),
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("2.1g", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("Carbohydrate", left = 0, top = 190, right = 100, bottom = 210),
            OcrLine("15.6g", left = 200, top = 190, right = 260, bottom = 210),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(124.0, result.calories!!, 0.001)
        assertEquals(3.2, result.proteinG!!, 0.001)
        assertEquals(2.1, result.fatG!!, 0.001)
        assertEquals(15.6, result.carbsG!!, 0.001)
        assertNull(result.servingQty)
        assertNull(result.servingUnit)
        assertTrue(!result.isEmpty)
    }
}
