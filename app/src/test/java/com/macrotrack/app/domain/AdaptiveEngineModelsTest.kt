package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveEngineModelsTest {
    @Test
    fun engineConfigDefaultsMatchThePythonReferenceExactly() {
        // adaptive_engine.py's EngineConfig dataclass defaults, copied verbatim.
        val config = EngineConfig()
        assertEquals(7700.0, config.kcalPerKg, 0.0)
        assertEquals(0.20, config.trendAlpha, 0.0)
        assertEquals(14, config.minimumHistoryDays)
        assertEquals(7, config.coverageWindowDays)
        assertEquals(6, config.minimumNutritionDaysPerWeek)
        assertEquals(1, config.minimumWeightDaysPerWeek)
        assertEquals(100.0, config.maximumExpenditureStepKcal, 0.0)
        assertEquals(1000.0, config.minimumExpenditureKcal, 0.0)
        assertEquals(6000.0, config.maximumExpenditureKcal, 0.0)
        assertEquals(1.8, config.defaultProteinGPerKg, 0.0)
        assertEquals(0.8, config.defaultFatGPerKg, 0.0)
    }

    @Test
    fun dailyRecordDefaultsToACompleteStatusWithNoValues() {
        val record = DailyRecord(day = java.time.LocalDate.of(2026, 7, 1))
        assertEquals("complete", record.nutritionStatus)
        assertEquals(null, record.calories)
        assertEquals(null, record.weightKg)
    }
}
