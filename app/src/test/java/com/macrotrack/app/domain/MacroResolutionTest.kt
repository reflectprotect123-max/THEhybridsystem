package com.macrotrack.app.domain

import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.FoodServing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MacroResolutionTest {
    private val oats = Food(
        id = "food-1", name = "Rolled Oats", brand = null, barcode = null,
        servingQty = 100.0, servingUnit = "g", calories = 379.0, proteinG = 13.2,
        carbsG = 67.7, fatG = 6.9, source = "ausnut", externalId = "12-345",
        nutritionBasisQty = 100.0, nutritionBasisUnit = "g", servingSizeText = "100 g",
    )
    private val cupServing = FoodServing(
        id = "serving-1", foodId = "food-1", label = "1 cup", quantity = 1.0,
        unit = "cup", grams = 90.0, millilitres = null, isDefault = true, sortOrder = 0,
    )

    @Test
    fun resolvesDirectlyWhenTheUnitMatchesTheFoodsServingUnit() {
        val result = MacroResolution.resolveFoodMacros(oats, servings = listOf(cupServing), quantity = 50.0, unit = "g")
        assertEquals(189.5, result.calories, 0.001)
    }

    @Test
    fun fallsBackToAMatchingFoodServingWhenTheUnitDoesNotMatch() {
        // 2 cups = 180 g -> 1.8x the 100g-basis macros, same math ServingScaler.scaleByServing already proved
        val result = MacroResolution.resolveFoodMacros(oats, servings = listOf(cupServing), quantity = 2.0, unit = "cup")
        assertEquals(682.2, result.calories, 0.001)
    }

    @Test
    fun throwsWhenNeitherTheDirectUnitNorAServingMatches() {
        assertThrows(ServingScaler.IncompatibleUnitException::class.java) {
            MacroResolution.resolveFoodMacros(oats, servings = listOf(cupServing), quantity = 1.0, unit = "tablespoon")
        }
    }

    @Test
    fun sumsMacrosAcrossItems() {
        val a = ScaledMacros(calories = 100.0, proteinG = 10.0, carbsG = 5.0, fatG = 2.0)
        val b = ScaledMacros(calories = 50.0, proteinG = 2.0, carbsG = 8.0, fatG = 1.0)
        val result = MacroResolution.sumMacros(listOf(a, b))
        assertEquals(150.0, result.calories, 0.001)
        assertEquals(12.0, result.proteinG, 0.001)
    }

    @Test
    fun dividesByRecipeServingsForPerServingMacros() {
        val total = ScaledMacros(calories = 900.0, proteinG = 60.0, carbsG = 90.0, fatG = 30.0)
        val result = MacroResolution.perServing(total, recipeServings = 3.0)
        assertEquals(300.0, result.calories, 0.001)
        assertEquals(20.0, result.proteinG, 0.001)
    }

    @Test
    fun multipliesPerServingByLoggedServings() {
        val perServing = ScaledMacros(calories = 300.0, proteinG = 20.0, carbsG = 30.0, fatG = 10.0)
        val result = MacroResolution.forLoggedServings(perServing, loggedServings = 1.5)
        assertEquals(450.0, result.calories, 0.001)
        assertEquals(30.0, result.proteinG, 0.001)
    }

    @Test
    fun rejectsZeroOrNegativeRecipeServings() {
        val total = ScaledMacros(100.0, 10.0, 10.0, 5.0)
        assertThrows(IllegalArgumentException::class.java) { MacroResolution.perServing(total, recipeServings = 0.0) }
    }

    @Test
    fun rejectsZeroOrNegativeLoggedServings() {
        val perServing = ScaledMacros(100.0, 10.0, 10.0, 5.0)
        assertThrows(IllegalArgumentException::class.java) { MacroResolution.forLoggedServings(perServing, loggedServings = -1.0) }
    }
}
