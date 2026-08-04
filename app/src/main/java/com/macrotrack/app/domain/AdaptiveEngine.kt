package com.macrotrack.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Deterministic reference engine -- a faithful Kotlin port of
 * adaptive_engine.py. Uses logged intake and smoothed weight change;
 * requires enough coverage; holds when data are inadequate; damps updates.
 * No wearable calorie estimates are used. A missing nutrition day is never
 * silently interpolated; a missing weight day may be tolerated by the trend
 * filter because the scale is a noisy measurement. See
 * docs/ADAPTIVE_ENGINE_CONTRACT.md.
 */
object AdaptiveEngine {

    /**
     * A declared fast is countable only when the caller explicitly stores
     * zero calories. An unlogged day remains unknown.
     */
    fun nutritionIsCountable(record: DailyRecord): Boolean {
        if (record.nutritionStatus == "fasted") {
            return record.calories == 0.0
        }
        return record.nutritionStatus == "complete" && record.calories != null && record.calories >= 0
    }

    /** Recent-weight-weighted EWMA, carrying the last trend across gaps. */
    fun weightTrend(values: List<Double?>, alpha: Double = 0.20): List<Double?> {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be greater than 0 and no greater than 1" }
        var previous: Double? = null
        return values.map { value ->
            if (value != null) {
                previous = previous?.let { alpha * value + (1 - alpha) * it } ?: value
            }
            previous
        }
    }

    private fun linearSlope(points: List<Pair<LocalDate, Double>>): Double? {
        if (points.size < 2) return null
        val origin = points[0].first
        val xs = points.map { (day, _) -> ChronoUnit.DAYS.between(origin, day).toDouble() }
        val ys = points.map { it.second }
        val xMean = xs.average()
        val yMean = ys.average()
        val denominator = xs.sumOf { (it - xMean) * (it - xMean) }
        if (denominator == 0.0) return null
        val numerator = xs.indices.sumOf { i -> (xs[i] - xMean) * (ys[i] - yMean) }
        return numerator / denominator
    }

    private fun periodCoverage(records: List<DailyRecord>, start: LocalDate, end: LocalDate): Pair<Int, Int> {
        val period = records.filter { it.day >= start && it.day <= end }
        val nutritionDays = period.count { nutritionIsCountable(it) }
        val weightDays = period.count { it.weightKg != null }
        return nutritionDays to weightDays
    }

    private class CoverageExplanation(val enough: Boolean, val reasons: List<String>)

    /**
     * Simplified relative to Python's `_coverage_explanation`, which returns
     * a 4-tuple `(enough, reasons, gate_nutrition, gate_weight)` -- the last
     * two elements are assigned in `estimate_expenditure` but never read
     * again, so they are not ported. Not a behavior change.
     */
    private fun coverageExplanation(records: List<DailyRecord>, config: EngineConfig): CoverageExplanation {
        if (records.isEmpty()) {
            return CoverageExplanation(false, listOf("No daily records are available."))
        }
        val first = records.first().day
        val last = records.last().day
        if (ChronoUnit.DAYS.between(first, last) + 1 < config.minimumHistoryDays) {
            return CoverageExplanation(false, listOf("More history is required before updating expenditure."))
        }
        val lastWeekStart = last.minusDays((config.coverageWindowDays - 1).toLong())
        val previousWeekEnd = lastWeekStart.minusDays(1)
        val previousWeekStart = previousWeekEnd.minusDays((config.coverageWindowDays - 1).toLong())
        val (currentNutrition, currentWeight) = periodCoverage(records, lastWeekStart, last)
        val (previousNutrition, previousWeight) = periodCoverage(records, previousWeekStart, previousWeekEnd)
        val reasons = mutableListOf<String>()
        if (currentNutrition < config.minimumNutritionDaysPerWeek || previousNutrition < config.minimumNutritionDaysPerWeek) {
            reasons.add("Nutrition logging is below the 6-of-7-day update gate.")
        }
        if (currentWeight < config.minimumWeightDaysPerWeek || previousWeight < config.minimumWeightDaysPerWeek) {
            reasons.add("At least one weigh-in is required in each seven-day period.")
        }
        return CoverageExplanation(reasons.isEmpty(), reasons)
    }

    /** Estimates TDEE as mean logged intake minus energy represented by trend slope. */
    fun estimateExpenditure(
        records: List<DailyRecord>,
        previousEstimateKcal: Double? = null,
        config: EngineConfig = EngineConfig(),
    ): ExpenditureEstimate {
        val ordered = records.sortedBy { it.day }
        if (ordered.isEmpty()) {
            return ExpenditureEstimate(
                state = "holding", confidence = "holding", estimateKcal = previousEstimateKcal,
                rawEstimateKcal = null, previousEstimateKcal = previousEstimateKcal,
                trendSlopeKgPerWeek = null, nutritionDays = 0, weightDays = 0,
                windowStart = null, windowEnd = null, explanation = "No records are available.",
            )
        }
        val coverage = coverageExplanation(ordered, config)
        val end = ordered.last().day
        val start = maxOf(ordered.first().day, end.minusDays((config.coverageWindowDays * 2 - 1).toLong()))
        val window = ordered.filter { it.day >= start && it.day <= end }
        val countable = window.filter { nutritionIsCountable(it) }
        val nutritionDays = countable.size
        val weightDays = window.count { it.weightKg != null }
        val trendValues = weightTrend(ordered.map { it.weightKg }, config.trendAlpha)
        val trendPoints = ordered.zip(trendValues)
            .filter { (record, trend) -> record.day >= start && record.day <= end && trend != null }
            .map { (record, trend) -> record.day to trend!! }
        val slopePerDay = linearSlope(trendPoints)
        val slopePerWeek = slopePerDay?.times(7.0)
        var raw: Double? = null
        if (coverage.enough && countable.isNotEmpty() && slopePerDay != null) {
            val meanCalories = countable.mapNotNull { it.calories }.average()
            val rawValue = meanCalories - slopePerDay * config.kcalPerKg
            raw = rawValue.coerceIn(config.minimumExpenditureKcal, config.maximumExpenditureKcal)
        }
        if (raw == null) {
            val confidence = if (previousEstimateKcal == null) "holding" else "low"
            val explanation = if (coverage.reasons.isNotEmpty()) {
                coverage.reasons.joinToString(" ")
            } else {
                "A trend slope and complete intake coverage are required."
            }
            return ExpenditureEstimate(
                state = "holding", confidence = confidence, estimateKcal = previousEstimateKcal,
                rawEstimateKcal = null, previousEstimateKcal = previousEstimateKcal,
                trendSlopeKgPerWeek = slopePerWeek, nutritionDays = nutritionDays,
                weightDays = weightDays, windowStart = start.toString(), windowEnd = end.toString(),
                explanation = explanation,
            )
        }
        val estimate = if (previousEstimateKcal == null) {
            raw
        } else {
            previousEstimateKcal + (raw - previousEstimateKcal).coerceIn(
                -config.maximumExpenditureStepKcal,
                config.maximumExpenditureStepKcal,
            )
        }
        val spanDays = ChronoUnit.DAYS.between(ordered.first().day, end) + 1
        val confidence = when {
            spanDays >= 28 && nutritionDays >= 12 && weightDays >= 4 -> "high"
            spanDays >= 14 -> "medium"
            else -> "low"
        }
        return ExpenditureEstimate(
            state = "updating", confidence = confidence, estimateKcal = round1(estimate),
            rawEstimateKcal = round1(raw), previousEstimateKcal = previousEstimateKcal,
            trendSlopeKgPerWeek = slopePerWeek?.let { round4(it) },
            nutritionDays = nutritionDays, weightDays = weightDays,
            windowStart = start.toString(), windowEnd = end.toString(),
            explanation = "Expenditure updated from logged intake and smoothed weight trend.",
        )
    }
}
