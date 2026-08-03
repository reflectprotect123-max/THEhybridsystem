package com.macrotrack.app.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Small Kotlin counterpart to adaptive_engine.py.
 *
 * Keep this pure and deterministic. The app should persist the inputs,
 * decision, and explanation for every weekly check-in.
 */
data class DailyNutritionRecord(
    val calories: Double?,
    val weightKg: Double?,
    val nutritionComplete: Boolean = true,
)

data class MacroTargets(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

object AdaptiveNutrition {
    const val KILOCALORIES_PER_KILOGRAM = 7700.0
    const val MAX_EXPENDITURE_STEP = 100.0

    fun weightTrend(values: List<Double?>, alpha: Double = 0.20): List<Double?> {
        require(alpha > 0.0 && alpha <= 1.0)
        var previous: Double? = null
        return values.map { value ->
            if (value != null) {
                previous = previous?.let { alpha * value + (1 - alpha) * it } ?: value
            }
            previous
        }
    }

    fun expenditureFromIntakeAndTrend(
        averageCalories: Double,
        trendSlopeKgPerWeek: Double,
    ): Double = averageCalories - trendSlopeKgPerWeek * KILOCALORIES_PER_KILOGRAM / 7.0

    fun dampExpenditure(previous: Double, observed: Double): Double {
        val delta = min(MAX_EXPENDITURE_STEP, max(-MAX_EXPENDITURE_STEP, observed - previous))
        return previous + delta
    }

    fun macroTargets(
        calories: Double,
        bodyWeightKg: Double,
        proteinGPerKg: Double = 1.8,
        fatGPerKg: Double = 0.8,
    ): MacroTargets {
        val protein = max(0.0, bodyWeightKg * proteinGPerKg)
        val fat = max(0.0, bodyWeightKg * fatGPerKg)
        val carbs = max(0.0, (calories - protein * 4.0 - fat * 9.0) / 4.0)
        return MacroTargets(calories, protein, carbs, fat)
    }
}
