package com.macrotrack.app.domain

import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.FoodServing

/**
 * Pure macro math that ServingScaler alone can't do: falling back to an
 * explicit FoodServing conversion when a logged unit doesn't match a
 * food's stored serving unit, and rolling a recipe's ingredients up into
 * per-serving macros. No I/O — callers (LogRepository, RecipeMacroResolver)
 * fetch the Food/FoodServing rows and pass them in already-resolved.
 */
object MacroResolution {

    fun resolveFoodMacros(food: Food, servings: List<FoodServing>, quantity: Double, unit: String): ScaledMacros {
        return try {
            ServingScaler.scale(food, quantity, unit)
        } catch (direct: ServingScaler.IncompatibleUnitException) {
            val matching = servings.firstOrNull { it.unit.equals(unit, ignoreCase = true) }
                ?: throw direct
            val servingCount = quantity / matching.quantity
            ServingScaler.scaleByServing(food, matching, servingCount)
        }
    }

    fun sumMacros(items: List<ScaledMacros>): ScaledMacros =
        items.fold(ScaledMacros(0.0, 0.0, 0.0, 0.0)) { acc, item ->
            ScaledMacros(
                calories = acc.calories + item.calories,
                proteinG = acc.proteinG + item.proteinG,
                carbsG = acc.carbsG + item.carbsG,
                fatG = acc.fatG + item.fatG,
            )
        }

    fun perServing(total: ScaledMacros, recipeServings: Double): ScaledMacros {
        require(recipeServings > 0) { "recipeServings must be > 0, got $recipeServings" }
        return ScaledMacros(
            calories = total.calories / recipeServings,
            proteinG = total.proteinG / recipeServings,
            carbsG = total.carbsG / recipeServings,
            fatG = total.fatG / recipeServings,
        )
    }

    fun forLoggedServings(perServingMacros: ScaledMacros, loggedServings: Double): ScaledMacros {
        require(loggedServings > 0) { "loggedServings must be > 0, got $loggedServings" }
        return ScaledMacros(
            calories = perServingMacros.calories * loggedServings,
            proteinG = perServingMacros.proteinG * loggedServings,
            carbsG = perServingMacros.carbsG * loggedServings,
            fatG = perServingMacros.fatG * loggedServings,
        )
    }
}
