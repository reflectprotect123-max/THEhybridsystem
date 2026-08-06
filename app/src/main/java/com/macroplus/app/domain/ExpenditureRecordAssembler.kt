package com.macroplus.app.domain

import com.macroplus.app.data.model.DailyLogStatus
import com.macroplus.app.data.model.DailyTotals
import com.macroplus.app.data.model.DayStatus
import java.time.LocalDate

/**
 * Combines per-day status, nutrition totals, and weight data into the dense,
 * one-row-per-calendar-day series `AdaptiveEngine.estimateExpenditure`
 * expects. A day with no status row is `DayStatus.UNLOGGED` (a missing row
 * and an explicit "unlogged" row are documented as equivalent -- see
 * `DayStatusModels.kt`); a day with no totals row has `calories = null`,
 * never a zero-fill, matching CLAUDE.md rule #2.
 *
 * A declared fast is the one exception: a real fasted day has no
 * `food_log_entries` rows at all (the user ate nothing), so
 * `daily_nutrition_totals` never has a row for it -- `totalsByDay[day]`
 * would be `null` exactly like an unlogged day. But
 * `AdaptiveEngine.nutritionIsCountable` only treats a fasted day as
 * countable when `calories == 0.0` explicitly (per
 * `docs/ADAPTIVE_ENGINE_CONTRACT.md`: "a fast is countable only when the
 * app stores zero calories explicitly"), so without this fallback every
 * fasted day would silently read as uncountable, identical to unlogged --
 * a user who fasts one day a week could never pass the 6-of-7 coverage
 * gate. This is the explicit user declaration the schema calls for
 * (`weight_entries`... `daily_log_status`'s own doc: "'fasted' is a user
 * declaration, not an inference from missing entries"), not an inferred
 * zero, so it does not violate CLAUDE.md rule #2 -- a day the user did
 * NOT declare fasted still gets `null`, never a zero-fill.
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
            val status = statusByDay[day] ?: DayStatus.UNLOGGED
            DailyRecord(
                day = day,
                calories = totalsByDay[day] ?: if (status == DayStatus.FASTED) 0.0 else null,
                weightKg = weightByDay[day],
                nutritionStatus = status,
            )
        }
    }
}
