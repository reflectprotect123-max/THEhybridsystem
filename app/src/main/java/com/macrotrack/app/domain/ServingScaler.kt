package com.macrotrack.app.domain

import com.macrotrack.app.data.model.FoodServing

data class ScaledMacros(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

/**
 * Scales a Scalable's stored macros (which are per `food.servingQty` of
 * `food.servingUnit`) to a requested quantity. Never converts between mass
 * and volume (e.g. g <-> ml) by guessing a density — CLAUDE.md's
 * non-negotiable rule #1. A cross-unit conversion is only allowed via an
 * explicit FoodServing row that records the real grams/millilitres for that
 * serving, because that value came from the source data, not a guess.
 */
object ServingScaler {
    class IncompatibleUnitException(message: String) : Exception(message)

    fun scale(food: Scalable, quantity: Double, unit: String): ScaledMacros {
        require(quantity.isFinite() && quantity > 0) { "quantity must be finite and > 0, got $quantity" }
        require(food.servingQty.isFinite() && food.servingQty > 0) {
            "${food.name} has an invalid serving quantity ${food.servingQty}"
        }
        if (!unit.equals(food.servingUnit, ignoreCase = true)) {
            throw IncompatibleUnitException(
                "Cannot scale ${food.name}: requested unit '$unit' does not match " +
                    "this food's serving unit '${food.servingUnit}'. Use a food_servings " +
                    "entry with an explicit gram/millilitre conversion instead of guessing a density."
            )
        }
        val multiplier = quantity / food.servingQty
        return scaleByMultiplier(food, multiplier)
    }

    fun scaleByServing(food: Scalable, serving: FoodServing, servingCount: Double = 1.0): ScaledMacros {
        require(servingCount.isFinite() && servingCount > 0) {
            "servingCount must be finite and > 0, got $servingCount"
        }
        require(serving.foodId == food.id) {
            "Serving ${serving.id} belongs to food ${serving.foodId}, not ${food.id}"
        }
        val basisUnit = food.servingUnit.lowercase()
        val perServingAmount = when (basisUnit) {
            "g" -> serving.grams
            "ml" -> serving.millilitres
            else -> null
        } ?: throw IncompatibleUnitException(
            "Serving '${serving.label}' for ${food.name} has no $basisUnit conversion recorded."
        )
        require(perServingAmount.isFinite() && perServingAmount > 0) {
            "Serving '${serving.label}' has an invalid amount $perServingAmount"
        }
        require(food.servingQty.isFinite() && food.servingQty > 0) {
            "${food.name} has an invalid serving quantity ${food.servingQty}"
        }
        val multiplier = (perServingAmount * servingCount) / food.servingQty
        return scaleByMultiplier(food, multiplier)
    }

    private fun scaleByMultiplier(food: Scalable, multiplier: Double) = ScaledMacros(
        calories = food.calories * multiplier,
        proteinG = food.proteinG * multiplier,
        carbsG = food.carbsG * multiplier,
        fatG = food.fatG * multiplier,
    )
}
