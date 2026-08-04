package com.macrotrack.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyCheckInTest {

    @Test
    fun checkInIsHeldWithModulesWhenHistoryIsTooShort() {
        // adaptive_engine.py test_check_in_returns_hold_modules: only 7 of the
        // 14-day minimum history, all days complete/logged.
        val start = LocalDate.of(2026, 7, 1)
        val records = (0 until 7).map { index ->
            DailyRecord(day = start.plusDays(index.toLong()), calories = 2800.0, weightKg = 90 - index * 0.04)
        }

        val result = weeklyCheckIn(
            records = records,
            previousExpenditureKcal = 2800.0,
            bodyWeightKg = 90.0,
            targetRateKgPerWeek = -0.3,
        )

        assertEquals("held", result.status)
        assertNull(result.targets)
        assertEquals("holding", result.estimate.state)
        assertEquals("low", result.estimate.confidence)
        assertEquals(2800.0, result.estimate.estimateKcal!!, 0.0001)
        assertNull(result.estimate.rawEstimateKcal)
        assertEquals(-0.14445567999998943, result.estimate.trendSlopeKgPerWeek!!, 0.00000000001)
        assertEquals(7, result.estimate.nutritionDays)
        assertEquals(7, result.estimate.weightDays)
        assertEquals("2026-07-01", result.estimate.windowStart)
        assertEquals("2026-07-07", result.estimate.windowEnd)
        assertEquals("More history is required before updating expenditure.", result.estimate.explanation)

        // Exactly these two modules, in this order -- not weigh_in, since
        // weight_days (7) is not below minimum_weight_days_per_week*2 (2).
        assertEquals(2, result.modules.size)
        assertEquals(CheckInModule("partial_logging", "review incomplete nutrition days"), result.modules[0])
        assertEquals(CheckInModule("logging_break", "carry forward the last high-confidence estimate"), result.modules[1])
    }

    @Test
    fun checkInIsReadyWithTargetsForTheDocumentedExampleScenario() {
        // Ports examples/checkin.json end-to-end. Expected values captured by
        // running `python3 adaptive_engine.py --input examples/checkin.json`
        // this session.
        val records = listOf(
            DailyRecord(LocalDate.of(2026, 7, 1), calories = 2850.0, weightKg = 89.2),
            DailyRecord(LocalDate.of(2026, 7, 2), calories = 2800.0, weightKg = 89.1),
            DailyRecord(LocalDate.of(2026, 7, 3), calories = 2780.0, weightKg = 89.0),
            DailyRecord(LocalDate.of(2026, 7, 4), calories = 2820.0, weightKg = 88.9),
            DailyRecord(LocalDate.of(2026, 7, 5), calories = 2790.0, weightKg = 88.8),
            DailyRecord(LocalDate.of(2026, 7, 6), calories = 2810.0, weightKg = 88.7),
            DailyRecord(LocalDate.of(2026, 7, 7), calories = 2800.0, weightKg = 88.6),
            DailyRecord(LocalDate.of(2026, 7, 8), calories = 2770.0, weightKg = 88.5),
            DailyRecord(LocalDate.of(2026, 7, 9), calories = 2790.0, weightKg = 88.4),
            DailyRecord(LocalDate.of(2026, 7, 10), calories = 2800.0, weightKg = 88.3),
            DailyRecord(LocalDate.of(2026, 7, 11), calories = 2810.0, weightKg = 88.2),
            DailyRecord(LocalDate.of(2026, 7, 12), calories = 2780.0, weightKg = 88.1),
            DailyRecord(LocalDate.of(2026, 7, 13), calories = 2800.0, weightKg = 88.0),
            DailyRecord(LocalDate.of(2026, 7, 14), calories = 2790.0, weightKg = 87.9),
        )

        val result = weeklyCheckIn(
            records = records,
            previousExpenditureKcal = 2800.0,
            bodyWeightKg = 89.0,
            targetRateKgPerWeek = -0.3,
            proteinGPerKg = 1.8,
            fatGPerKg = 0.8,
        )

        assertEquals("ready", result.status)
        assertEquals("updating", result.estimate.state)
        assertEquals("medium", result.estimate.confidence)
        assertEquals(2900.0, result.estimate.estimateKcal!!, 0.0001)
        assertEquals(3365.8, result.estimate.rawEstimateKcal!!, 0.0001)
        assertEquals(-0.515, result.estimate.trendSlopeKgPerWeek!!, 0.0001)
        assertEquals(14, result.estimate.nutritionDays)
        assertEquals(14, result.estimate.weightDays)
        assertEquals("2026-07-01", result.estimate.windowStart)
        assertEquals("2026-07-14", result.estimate.windowEnd)

        val targets = result.targets!!
        assertEquals(2570.0, targets.calories, 0.0001)
        assertEquals(160.2, targets.proteinG, 0.0001)
        assertEquals(322.1, targets.carbsG, 0.0001)
        assertEquals(71.2, targets.fatG, 0.0001)
        assertEquals(2570.0, targets.macroCalories, 0.0001)
        assertEquals(
            listOf(CheckInModule("program_update", "review and accept the proposed calorie and macro targets")),
            result.modules,
        )
    }
}
