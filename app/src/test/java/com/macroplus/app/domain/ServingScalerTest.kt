package com.macroplus.app.domain

import com.macroplus.app.data.model.CustomFood
import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodServing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServingScalerTest {
    private val oats = Food(
        id = "food-1",
        name = "Rolled Oats",
        brand = null,
        barcode = null,
        servingQty = 100.0,
        servingUnit = "g",
        calories = 379.0,
        proteinG = 13.2,
        carbsG = 67.7,
        fatG = 6.9,
        source = "ausnut",
        externalId = "12-345",
        nutritionBasisQty = 100.0,
        nutritionBasisUnit = "g",
        servingSizeText = "100 g",
    )

    @Test
    fun scalesMacrosLinearlyWithinTheSameUnit() {
        val result = ServingScaler.scale(oats, quantity = 250.0, unit = "g")

        assertEquals(947.5, result.calories, 0.001)
        assertEquals(33.0, result.proteinG, 0.001)
        assertEquals(169.25, result.carbsG, 0.001)
        assertEquals(17.25, result.fatG, 0.001)
    }

    @Test
    fun throwsRatherThanGuessingAUnitConversion() {
        assertThrows(ServingScaler.IncompatibleUnitException::class.java) {
            ServingScaler.scale(oats, quantity = 1.0, unit = "cup")
        }
    }

    @Test
    fun scalesUsingAnExplicitFoodServingConversion() {
        val cupServing = FoodServing(
            id = "serving-1",
            foodId = "food-1",
            label = "1 cup",
            quantity = 1.0,
            unit = "cup",
            grams = 90.0,
            millilitres = null,
            isDefault = true,
            sortOrder = 0,
        )

        val result = ServingScaler.scaleByServing(oats, cupServing, servingCount = 2.0)

        // 2 cups = 180 g; 180 / 100 = 1.8x
        assertEquals(682.2, result.calories, 0.001)
        assertEquals(23.76, result.proteinG, 0.001)
    }

    @Test
    fun throwsWhenTheServingHasNoConversionForTheFoodsBasisUnit() {
        val servingWithoutGrams = FoodServing(
            id = "serving-2",
            foodId = "food-1",
            label = "1 packet",
            quantity = 1.0,
            unit = "packet",
            grams = null,
            millilitres = null,
            isDefault = false,
            sortOrder = 1,
        )

        assertThrows(ServingScaler.IncompatibleUnitException::class.java) {
            ServingScaler.scaleByServing(oats, servingWithoutGrams)
        }
    }

    @Test
    fun rejectsAServingThatBelongsToADifferentFood() {
        val mismatchedServing = FoodServing(
            id = "serving-3",
            foodId = "food-999",
            label = "1 cup",
            quantity = 1.0,
            unit = "cup",
            grams = 90.0,
            millilitres = null,
            isDefault = true,
            sortOrder = 0,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ServingScaler.scaleByServing(oats, mismatchedServing)
        }
    }

    @Test
    fun scalesACustomFoodTheSameWayAsAFood() {
        val customShake = CustomFood(
            id = "custom-1",
            userId = "user-1",
            name = "My Protein Shake",
            brand = null,
            barcode = null,
            servingQty = 250.0,
            servingUnit = "ml",
            calories = 180.0,
            proteinG = 30.0,
            carbsG = 9.0,
            fatG = 2.0,
        )

        val result = ServingScaler.scale(customShake, quantity = 500.0, unit = "ml")

        assertEquals(360.0, result.calories, 0.001)
        assertEquals(60.0, result.proteinG, 0.001)
    }
}
