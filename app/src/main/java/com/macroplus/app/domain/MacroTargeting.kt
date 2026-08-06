package com.macroplus.app.domain

/** Mirrors adaptive_engine.py's macro_targets() return dict. */
data class MacroTargets(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val macroCalories: Double,
)

/**
 * Returns a transparent starting estimate; observed data
 * (AdaptiveEngine.estimateExpenditure) should replace it as soon as coverage
 * allows.
 */
fun initialExpenditureKcal(
    weightKg: Double,
    heightCm: Double,
    ageYears: Double,
    sex: String = "unspecified",
    activityLevel: String = "moderate",
    bodyFatPct: Double? = null,
): Double {
    val bmr = if (bodyFatPct != null && bodyFatPct in 2.0..70.0) {
        val leanMass = weightKg * (1 - bodyFatPct / 100)
        370 + 21.6 * leanMass
    } else {
        when (sex) {
            "male" -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears + 5
            "female" -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears - 161
            // Midpoint fallback rather than guessing a sex-specific equation.
            else -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears - 78
        }
    }
    val factors = mapOf(
        "sedentary" to 1.20,
        "light" to 1.375,
        "moderate" to 1.55,
        "high" to 1.725,
        "very_high" to 1.90,
    )
    val factor = factors[activityLevel] ?: factors.getValue("moderate")
    return round1(maxOf(1000.0, bmr * factor))
}

/** Signed rate: negative loses weight, positive gains weight, zero maintains. */
fun calorieTarget(
    expenditureKcal: Double,
    targetRateKgPerWeek: Double,
    config: EngineConfig = EngineConfig(),
): Double = round1(expenditureKcal + targetRateKgPerWeek * config.kcalPerKg / 7)

fun macroTargets(
    calories: Double,
    bodyWeightKg: Double,
    proteinGPerKg: Double = EngineConfig().defaultProteinGPerKg,
    fatGPerKg: Double = EngineConfig().defaultFatGPerKg,
): MacroTargets {
    val protein = maxOf(0.0, bodyWeightKg * proteinGPerKg)
    val fat = maxOf(0.0, bodyWeightKg * fatGPerKg)
    val remaining = calories - protein * 4 - fat * 9
    val carbs = maxOf(0.0, remaining / 4)
    return MacroTargets(
        calories = round1(calories),
        proteinG = round1(protein),
        carbsG = round1(carbs),
        fatG = round1(fat),
        macroCalories = round1(protein * 4 + carbs * 4 + fat * 9),
    )
}
