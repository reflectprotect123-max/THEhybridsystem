package com.macrotrack.app.domain

import java.time.LocalDate

/**
 * Product parameters for the adaptive engine -- mirrors adaptive_engine.py's
 * EngineConfig dataclass field-for-field, including its defaults. These are
 * this repository's explicit product choices, not validated reconstructions
 * of any third-party app's private parameters (see
 * docs/ADAPTIVE_ENGINE_CONTRACT.md). Keep them versioned and test-covered
 * before changing any of them.
 */
data class EngineConfig(
    val kcalPerKg: Double = 7700.0,
    val trendAlpha: Double = 0.20,
    val minimumHistoryDays: Int = 14,
    val coverageWindowDays: Int = 7,
    val minimumNutritionDaysPerWeek: Int = 6,
    val minimumWeightDaysPerWeek: Int = 1,
    val maximumExpenditureStepKcal: Double = 100.0,
    val minimumExpenditureKcal: Double = 1000.0,
    val maximumExpenditureKcal: Double = 6000.0,
    val defaultProteinGPerKg: Double = 1.8,
    val defaultFatGPerKg: Double = 0.8,
)

/**
 * One calendar day of input to the engine. `nutritionStatus` is one of
 * "complete", "partial", "fasted", "unlogged" -- see
 * AdaptiveEngine.nutritionIsCountable for what each means.
 */
data class DailyRecord(
    val day: LocalDate,
    val calories: Double? = null,
    val weightKg: Double? = null,
    val nutritionStatus: String = "complete",
)

/**
 * Mirrors adaptive_engine.py's ExpenditureEstimate dataclass. windowStart/
 * windowEnd are ISO date strings -- LocalDate.toString() already produces
 * yyyy-MM-dd, matching Python's date.isoformat().
 */
data class ExpenditureEstimate(
    val state: String,
    val confidence: String,
    val estimateKcal: Double?,
    val rawEstimateKcal: Double?,
    val previousEstimateKcal: Double?,
    val trendSlopeKgPerWeek: Double?,
    val nutritionDays: Int,
    val weightDays: Int,
    val windowStart: String?,
    val windowEnd: String?,
    val explanation: String,
)
