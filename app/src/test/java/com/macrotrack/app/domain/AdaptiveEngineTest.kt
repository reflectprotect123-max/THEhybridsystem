package com.macrotrack.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveEngineTest {

    @Test
    fun nutritionIsCountableForACompleteDayWithNonNegativeCalories() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = 2200.0, nutritionStatus = "complete")
        assertTrue(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun nutritionIsNotCountableForACompleteDayWithNoCaloriesRecorded() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = null, nutritionStatus = "complete")
        assertFalse(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun nutritionIsNotCountableForAPartialDay() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = 1200.0, nutritionStatus = "partial")
        assertFalse(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun nutritionIsNotCountableForAnUnloggedDay() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = null, nutritionStatus = "unlogged")
        assertFalse(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun aDeclaredFastIsCountableOnlyWithExplicitZeroCalories() {
        val declaredZero = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = 0.0, nutritionStatus = "fasted")
        val declaredWithoutZero = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = null, nutritionStatus = "fasted")
        assertTrue(AdaptiveEngine.nutritionIsCountable(declaredZero))
        assertFalse(AdaptiveEngine.nutritionIsCountable(declaredWithoutZero))
    }

    @Test
    fun weightTrendSmoothsASpikeAcrossFiveDays() {
        // adaptive_engine.py: weight_trend([90.0, 90.2, 90.1, 95.0, 90.2], alpha=0.2)
        val trend = AdaptiveEngine.weightTrend(listOf(90.0, 90.2, 90.1, 95.0, 90.2), alpha = 0.2)
        assertEquals(5, trend.size)
        assertEquals(90.0, trend[0]!!, 0.0001)
        assertEquals(90.04, trend[1]!!, 0.0001)
        assertEquals(90.052, trend[2]!!, 0.0001)
        assertEquals(91.0416, trend[3]!!, 0.0001)
        assertEquals(90.87328000000001, trend[4]!!, 0.00000001)
    }

    private fun fullCoverageRecords(count: Int = 14): List<DailyRecord> {
        val start = LocalDate.of(2026, 7, 1)
        return (0 until count).map { index ->
            DailyRecord(day = start.plusDays(index.toLong()), calories = 2800.0, weightKg = 90 - index * 0.04)
        }
    }

    @Test
    fun estimateExpenditureUpdatesWithFullCoverage() {
        // adaptive_engine.py test_expenditure_updates_only_with_coverage (updating branch)
        val estimate = AdaptiveEngine.estimateExpenditure(fullCoverageRecords(), previousEstimateKcal = 2800.0)

        assertEquals("updating", estimate.state)
        assertEquals("medium", estimate.confidence)
        assertEquals(2900.0, estimate.estimateKcal!!, 0.0001)
        assertEquals(3026.6, estimate.rawEstimateKcal!!, 0.0001)
        assertEquals(2800.0, estimate.previousEstimateKcal!!, 0.0001)
        assertEquals(-0.206, estimate.trendSlopeKgPerWeek!!, 0.0001)
        assertEquals(14, estimate.nutritionDays)
        assertEquals(14, estimate.weightDays)
        assertEquals("2026-07-01", estimate.windowStart)
        assertEquals("2026-07-14", estimate.windowEnd)
        assertEquals("Expenditure updated from logged intake and smoothed weight trend.", estimate.explanation)
    }

    @Test
    fun estimateExpenditureHoldsWhenTwoDaysAreOnlyPartiallyLogged() {
        // adaptive_engine.py test_expenditure_updates_only_with_coverage (holding branch)
        val records = fullCoverageRecords().toMutableList()
        records[2] = records[2].copy(calories = null, nutritionStatus = "partial")
        records[3] = records[3].copy(calories = null, nutritionStatus = "partial")

        val held = AdaptiveEngine.estimateExpenditure(records, previousEstimateKcal = 2800.0)

        assertEquals("holding", held.state)
        assertEquals("low", held.confidence)
        assertEquals(2800.0, held.estimateKcal!!, 0.0001)
        assertNull(held.rawEstimateKcal)
        assertEquals(2800.0, held.previousEstimateKcal!!, 0.0001)
        // Unrounded -- the holding branch never applies round4, unlike the updating branch.
        assertEquals(-0.20601168372240294, held.trendSlopeKgPerWeek!!, 0.00000000001)
        assertEquals(12, held.nutritionDays)
        assertEquals(14, held.weightDays)
        assertEquals("2026-07-01", held.windowStart)
        assertEquals("2026-07-14", held.windowEnd)
        assertEquals("Nutrition logging is below the 6-of-7-day update gate.", held.explanation)
    }
}
