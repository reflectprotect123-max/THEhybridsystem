package com.macrotrack.app.domain

import com.macrotrack.app.data.model.DailyLogStatus
import com.macrotrack.app.data.model.DailyTotals
import com.macrotrack.app.data.model.DayStatus
import java.time.LocalDate

/**
 * Combines per-day status, nutrition totals, and weight data into the dense,
 * one-row-per-calendar-day series `AdaptiveEngine.estimateExpenditure`
 * expects. A day with no status row is `DayStatus.UNLOGGED` (a missing row
 * and an explicit "unlogged" row are documented as equivalent -- see
 * `DayStatusModels.kt`); a day with no totals row has `calories = null`,
 * never a zero-fill, matching CLAUDE.md rule #2.
 */
object ExpenditureRecordAssembler {

    fun assemble(
        statuses: List<DailyLogStatus>,
        totals: List<DailyTotals>,
        weightByDay: Map<LocalDate, Double>,
        start: LocalDate,
        end: LocalDate,
    ): List<DailyRecord> {
        require(!end.isBefore(start)) { "end must not be before start, got start=$start end=$end" }
        val statusByDay = statuses.associate { LocalDate.parse(it.logDate) to it.status }
        val totalsByDay = totals.associate { LocalDate.parse(it.logDate) to it.calories }

        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        return days.map { day ->
            DailyRecord(
                day = day,
                calories = totalsByDay[day],
                weightKg = weightByDay[day],
                nutritionStatus = statusByDay[day] ?: DayStatus.UNLOGGED,
            )
        }
    }
}
