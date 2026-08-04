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

    @Test
    fun convertsKilojoulesToCaloriesWhenNoCalReadingIsPresent() {
        val lines = listOf(
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("836kJ", left = 200, top = 100, right = 260, bottom = 120),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(199.809, result.calories!!, 0.01)
    }

    @Test
    fun doesNotConfuseSaturatedFatOrSugarsWithTheTotalRow() {
        val lines = listOf(
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("9.4g", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("- saturated", left = 10, top = 190, right = 100, bottom = 210),
            OcrLine("6.1g", left = 200, top = 190, right = 250, bottom = 210),
            OcrLine("Carbohydrate", left = 0, top = 220, right = 100, bottom = 240),
            OcrLine("22.0g", left = 200, top = 220, right = 260, bottom = 240),
            OcrLine("- sugars", left = 10, top = 250, right = 100, bottom = 270),
            OcrLine("18.5g", left = 200, top = 250, right = 260, bottom = 270),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(9.4, result.fatG!!, 0.001)
        assertEquals(22.0, result.carbsG!!, 0.001)
    }

    @Test
    fun survivesLinesReturnedOutOfTopToBottomOrder() {
        // Same four rows as parsesACleanFourRowLabel, but shuffled - OCR does
        // not guarantee it returns lines in reading order.
        val lines = listOf(
            OcrLine("15.6g", left = 200, top = 190, right = 260, bottom = 210),
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("Carbohydrate", left = 0, top = 190, right = 100, bottom = 210),
            OcrLine("2.1g", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("124Cal (520kJ)", left = 200, top = 100, right = 320, bottom = 120),
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("Protein", left = 0, top = 130, right = 80, bottom = 150),
            OcrLine("3.2g", left = 200, top = 130, right = 250, bottom = 150),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(124.0, result.calories!!, 0.001)
        assertEquals(3.2, result.proteinG!!, 0.001)
        assertEquals(2.1, result.fatG!!, 0.001)
        assertEquals(15.6, result.carbsG!!, 0.001)
    }
}
