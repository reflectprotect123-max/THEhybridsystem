package com.macroplus.app.domain

import com.macroplus.app.data.model.DailyLogStatus
import com.macroplus.app.data.model.DailyTotals
import com.macroplus.app.data.model.DayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ExpenditureRecordAssemblerTest {

    @Test
    fun aDayWithNothingLoggedBecomesUnlogged() {
        val day = LocalDate.of(2026, 8, 1)

        val result = ExpenditureRecordAssembler.assemble(
            statuses = emptyList(),
            totals = emptyList(),
            weightByDay = emptyMap(),
            start = day,
            end = day,
        )

        assertEquals(1, result.size)
        assertEquals(day, result[0].day)
        assertEquals(DayStatus.UNLOGGED, result[0].nutritionStatus)
        assertNull(result[0].calories)
        assertNull(result[0].weightKg)
    }

    @Test
    fun aDayWithAllThreeSourcesPresentCarriesAllThreeValues() {
        val day = LocalDate.of(2026, 8, 2)
        val statuses = listOf(DailyLogStatus(userId = "user-1", logDate = day.toString(), status = DayStatus.COMPLETE))
        val totals = listOf(
            DailyTotals(
                userId = "user-1", logDate = day.toString(), calories = 2100.0,
                proteinG = 160.0, carbsG = 220.0, fatG = 70.0, entryCount = 3,
            )
        )
        val weightByDay = mapOf(day to 81.4)

        val result = ExpenditureRecordAssembler.assemble(statuses, totals, weightByDay, day, day)

        assertEquals(1, result.size)
        assertEquals(DayStatus.COMPLETE, result[0].nutritionStatus)
        assertEquals(2100.0, result[0].calories!!, 0.001)
        assertEquals(81.4, result[0].weightKg!!, 0.001)
    }

    @Test
    fun aFastedDayKeepsItsExplicitZeroCalories() {
        val day = LocalDate.of(2026, 8, 3)
        val statuses = listOf(DailyLogStatus(userId = "user-1", logDate = day.toString(), status = DayStatus.FASTED))
        val totals = listOf(
            DailyTotals(
                userId = "user-1", logDate = day.toString(), calories = 0.0,
                proteinG = 0.0, carbsG = 0.0, fatG = 0.0, entryCount = 1,
            )
        )

        val result = ExpenditureRecordAssembler.assemble(statuses, totals, emptyMap(), day, day)

        assertEquals(DayStatus.FASTED, result[0].nutritionStatus)
        assertEquals(0.0, result[0].calories!!, 0.001)
        assertEquals(true, AdaptiveEngine.nutritionIsCountable(result[0]))
    }

    @Test
    fun aFastedDayWithNoTotalsRowAtAllIsStillCountedAsZeroCalories() {
        // The realistic shape: a real fasted day has no food_log_entries at
        // all, so daily_nutrition_totals never has a row for it -- there is
        // no DailyTotals to hand-feed here, unlike aFastedDayKeepsItsExplicitZeroCalories.
        val day = LocalDate.of(2026, 8, 4)
        val statuses = listOf(DailyLogStatus(userId = "user-1", logDate = day.toString(), status = DayStatus.FASTED))

        val result = ExpenditureRecordAssembler.assemble(statuses, emptyList(), emptyMap(), day, day)

        assertEquals(DayStatus.FASTED, result[0].nutritionStatus)
        assertEquals(0.0, result[0].calories!!, 0.001)
        assertEquals(true, AdaptiveEngine.nutritionIsCountable(result[0]))
    }

    @Test
    fun anUnlogedDayIsNotGivenAZeroFallback() {
        val day = LocalDate.of(2026, 8, 5)
        val statuses = listOf(DailyLogStatus(userId = "user-1", logDate = day.toString(), status = DayStatus.UNLOGGED))

        val result = ExpenditureRecordAssembler.assemble(statuses, emptyList(), emptyMap(), day, day)

        assertNull(result[0].calories)
        assertEquals(false, AdaptiveEngine.nutritionIsCountable(result[0]))
    }

    @Test
    fun aMultiDayRangeIsAssembledInAscendingOrder() {
        val day1 = LocalDate.of(2026, 8, 1)
        val day2 = LocalDate.of(2026, 8, 2)
        val day3 = LocalDate.of(2026, 8, 3)
        val statuses = listOf(
            DailyLogStatus(userId = "user-1", logDate = day1.toString(), status = DayStatus.COMPLETE),
            DailyLogStatus(userId = "user-1", logDate = day3.toString(), status = DayStatus.COMPLETE),
        )

        val result = ExpenditureRecordAssembler.assemble(statuses, emptyList(), emptyMap(), day1, day3)

        assertEquals(listOf(day1, day2, day3), result.map { it.day })
        assertEquals(DayStatus.COMPLETE, result[0].nutritionStatus)
        assertEquals(DayStatus.UNLOGGED, result[1].nutritionStatus) // day2 has no status row
        assertEquals(DayStatus.COMPLETE, result[2].nutritionStatus)
    }
}
