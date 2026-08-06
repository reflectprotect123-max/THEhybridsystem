package com.macroplus.app.domain

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

    @Test
    fun parsesServingSizeFromParentheticalGrams() {
        val lines = listOf(
            OcrLine("Serving size: 2 biscuits (30g)", left = 0, top = 50, right = 300, bottom = 70),
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("124Cal", left = 200, top = 100, right = 260, bottom = 120),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(30.0, result.servingQty!!, 0.001)
        assertEquals("g", result.servingUnit)
    }

    @Test
    fun leavesServingSizeNullWhenNoServingSizeLineIsPresent() {
        val lines = listOf(
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("124Cal", left = 200, top = 100, right = 260, bottom = 120),
        )

        val result = NutritionLabelParser.parse(lines)

        assertNull(result.servingQty)
        assertNull(result.servingUnit)
    }

    @Test
    fun choosesThePerServingColumnOverAPer100gColumnWhenBothArePresent() {
        // Standard FSANZ two-column panels list "per serving" before
        // "per 100g" - this locks in that the leftmost value cell (per
        // serving, the design's explicit choice over defaulting to 100g) is
        // the one read, not whichever cell happens to be widest or last.
        val lines = listOf(
            OcrLine("Protein", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("3.2g", left = 200, top = 100, right = 250, bottom = 120),
            OcrLine("10.7g", left = 300, top = 100, right = 350, bottom = 120),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(3.2, result.proteinG!!, 0.001)
    }

    @Test
    fun doesNotConfuseCommaPhrasedSaturatedFatOrSugarsWithTheTotalRow() {
        // Same guard as doesNotConfuseSaturatedFatOrSugarsWithTheTotalRow,
        // but against the comma-phrased sub-row convention ("Fat, saturated")
        // rather than a dash-prefixed one - a real AU label style the
        // dash-only fixture doesn't exercise.
        val lines = listOf(
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("9.4g", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("Fat, saturated", left = 0, top = 190, right = 100, bottom = 210),
            OcrLine("6.1g", left = 200, top = 190, right = 250, bottom = 210),
            OcrLine("Carbohydrate", left = 0, top = 220, right = 100, bottom = 240),
            OcrLine("22.0g", left = 200, top = 220, right = 260, bottom = 240),
            OcrLine("Carbohydrate, sugars", left = 0, top = 250, right = 140, bottom = 270),
            OcrLine("18.5g", left = 200, top = 250, right = 260, bottom = 270),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(9.4, result.fatG!!, 0.001)
        assertEquals(22.0, result.carbsG!!, 0.001)
    }

    @Test
    fun leavesFatBlankRatherThanUsingTheSaturatedSubRowsValueWhenTheTotalRowsValueIsUnreadable() {
        // Unlike doesNotConfuseSaturatedFatOrSugarsWithTheTotalRow above,
        // this fixture makes the total row's own value unreadable, so the
        // "saturated"-exclusion guard is the only thing standing between a
        // correct blank (fail-safe) and silently reading the sub-row's value
        // as if it were the total (CLAUDE.md rule #1: a wrong number is worse
        // than a blank one). If the guard were ever removed, fatG would come
        // back 6.1 here instead of null.
        val lines = listOf(
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("??", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("Fat, saturated", left = 0, top = 190, right = 100, bottom = 210),
            OcrLine("6.1g", left = 200, top = 190, right = 250, bottom = 210),
        )

        val result = NutritionLabelParser.parse(lines)

        assertNull(result.fatG)
    }

    @Test
    fun leavesCarbsBlankRatherThanUsingTheSugarsSubRowsValueWhenTheTotalRowsValueIsUnreadable() {
        val lines = listOf(
            OcrLine("Carbohydrate", left = 0, top = 220, right = 100, bottom = 240),
            OcrLine("??", left = 200, top = 220, right = 260, bottom = 240),
            OcrLine("Carbohydrate, sugars", left = 0, top = 250, right = 140, bottom = 270),
            OcrLine("18.5g", left = 200, top = 250, right = 260, bottom = 270),
        )

        val result = NutritionLabelParser.parse(lines)

        assertNull(result.carbsG)
    }

    @Test
    fun stillGroupsMacroRowsCorrectlyWhenHalfTheRecognizedLinesAreMuchTaller() {
        // A photo can pick up as much unrelated tall text (a title,
        // ingredients list) as there are lines in the macro table itself.
        // Estimating row spacing from a median/average line height can tip
        // onto the tall lines once they're roughly half the total, widening
        // the tolerance enough to chain-merge every macro row into one and
        // read values from the wrong row entirely - anchoring on the
        // *minimum* line height instead keeps row spacing tied to the
        // table's own (smaller, tightly and consistently set) text
        // regardless of how much larger unrelated text also appears.
        val tallFillerLines = (0 until 8).map { i ->
            OcrLine("Filler line $i", left = 0, top = i * 150, right = 300, bottom = i * 150 + 100)
        }
        val macroTableLines = listOf(
            OcrLine("Energy", left = 0, top = 1300, right = 80, bottom = 1320),
            OcrLine("124Cal", left = 200, top = 1300, right = 260, bottom = 1320),
            OcrLine("Protein", left = 0, top = 1330, right = 80, bottom = 1350),
            OcrLine("3.2g", left = 200, top = 1330, right = 250, bottom = 1350),
            OcrLine("Fat, total", left = 0, top = 1360, right = 90, bottom = 1380),
            OcrLine("2.1g", left = 200, top = 1360, right = 250, bottom = 1380),
            OcrLine("Carbohydrate", left = 0, top = 1390, right = 100, bottom = 1410),
            OcrLine("15.6g", left = 200, top = 1390, right = 260, bottom = 1410),
        )

        val result = NutritionLabelParser.parse(tallFillerLines + macroTableLines)

        assertEquals(124.0, result.calories!!, 0.001)
        assertEquals(3.2, result.proteinG!!, 0.001)
        assertEquals(2.1, result.fatG!!, 0.001)
        assertEquals(15.6, result.carbsG!!, 0.001)
    }

    @Test
    fun returnsAnEmptyResultWhenNothingRecognizableIsFound() {
        val lines = listOf(
            OcrLine("Ingredients: wheat flour, sugar, vegetable oil", left = 0, top = 300, right = 400, bottom = 320),
            OcrLine("Best before 12/2027", left = 0, top = 340, right = 200, bottom = 360),
        )

        val result = NutritionLabelParser.parse(lines)

        assertTrue(result.isEmpty)
    }
}
