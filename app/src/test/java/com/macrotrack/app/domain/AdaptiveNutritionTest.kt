package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNutritionTest {
    @Test
    fun trendCarriesTheLastSmoothedValueAcrossADataGap() {
        val trend = AdaptiveNutrition.weightTrend(listOf(90.0, 90.2, null, 90.1))
        assertEquals(4, trend.size)
        assertTrue(trend[2] != null)
    }

    @Test
    fun expenditureFallsWhenTheWeightTrendIsNegative() {
        val estimate = AdaptiveNutrition.expenditureFromIntakeAndTrend(
            averageCalories = 2800.0,
            trendSlopeKgPerWeek = -0.5,
        )
        assertTrue(estimate > 2800.0)
    }

    @Test
    fun macroTargetsUseTheConfiguredPreferences() {
        val targets = AdaptiveNutrition.macroTargets(
            calories = 2500.0,
            bodyWeightKg = 90.0,
            proteinGPerKg = 2.0,
            fatGPerKg = 0.8,
        )
        assertEquals(180.0, targets.proteinG, 0.001)
        assertEquals(72.0, targets.fatG, 0.001)
        assertTrue(targets.carbsG >= 0.0)
    }
}
