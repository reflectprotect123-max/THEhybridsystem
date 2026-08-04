package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroTargetingTest {

    @Test
    fun calorieTargetIsSignedByGoalRate() {
        // adaptive_engine.py: calorie_target(2800, -0.5) == 2250.0; calorie_target(2800, 0.5) == 3350.0
        assertEquals(2250.0, calorieTarget(2800.0, -0.5), 0.0001)
        assertEquals(3350.0, calorieTarget(2800.0, 0.5), 0.0001)
    }

    @Test
    fun macroTargetsAllocateFromExplicitPreferences() {
        // adaptive_engine.py: macro_targets(2500, 90, protein_g_per_kg=2.0, fat_g_per_kg=0.8)
        val targets = macroTargets(2500.0, 90.0, proteinGPerKg = 2.0, fatGPerKg = 0.8)
        assertEquals(2500.0, targets.calories, 0.0001)
        assertEquals(180.0, targets.proteinG, 0.0001)
        assertEquals(283.0, targets.carbsG, 0.0001)
        assertEquals(72.0, targets.fatG, 0.0001)
        assertEquals(2500.0, targets.macroCalories, 0.0001)
        assertTrue(targets.carbsG >= 0.0)
    }

    @Test
    fun initialExpenditureUsesMifflinStJeorForMales() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=90, height_cm=177, age_years=32, sex="male", activity_level="moderate") == 2869.4
        val estimate = initialExpenditureKcal(weightKg = 90.0, heightCm = 177.0, ageYears = 32.0, sex = "male", activityLevel = "moderate")
        assertEquals(2869.4, estimate, 0.0001)
    }

    @Test
    fun initialExpenditureUsesMifflinStJeorForFemales() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=65, height_cm=165, age_years=28, sex="female", activity_level="light") == 1897.8
        val estimate = initialExpenditureKcal(weightKg = 65.0, heightCm = 165.0, ageYears = 28.0, sex = "female", activityLevel = "light")
        assertEquals(1897.8, estimate, 0.0001)
    }

    @Test
    fun initialExpenditureUsesKatchMcArdleWhenBodyFatIsGiven() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=80, height_cm=178, age_years=30, sex="male", activity_level="high", body_fat_pct=18) == 3082.5
        val estimate = initialExpenditureKcal(weightKg = 80.0, heightCm = 178.0, ageYears = 30.0, sex = "male", activityLevel = "high", bodyFatPct = 18.0)
        assertEquals(3082.5, estimate, 0.0001)
    }

    @Test
    fun initialExpenditureUsesAMidpointFallbackForUnspecifiedSex() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=70, height_cm=170, age_years=25, sex="unspecified", activity_level="sedentary") == 1871.4
        val estimate = initialExpenditureKcal(weightKg = 70.0, heightCm = 170.0, ageYears = 25.0, sex = "unspecified", activityLevel = "sedentary")
        assertEquals(1871.4, estimate, 0.0001)
    }
}
