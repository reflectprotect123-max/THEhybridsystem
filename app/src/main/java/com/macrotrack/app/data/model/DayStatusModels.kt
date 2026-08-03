package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Values `daily_log_status.status`'s DB check constraint allows — copied verbatim, not re-derived. */
object DayStatus {
    const val COMPLETE = "complete"
    const val PARTIAL = "partial"
    const val FASTED = "fasted"
    const val UNLOGGED = "unlogged"
}

/**
 * Mirrors `public.daily_log_status`. Primary key is (user_id, log_date), so
 * this same shape works for both decode and upsert — there's no separate
 * `id` column to omit on insert.
 *
 * A missing row for a date and an explicit `status = "unlogged"` row both
 * mean "nothing declared for this day" in practice — DayStatusRepository's
 * `getStatus` returns null for the former; callers should treat null and
 * an explicit UNLOGGED status the same way unless they specifically care
 * about the difference between "never asked" and "explicitly marked".
 */
@Serializable
data class DailyLogStatus(
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val status: String,
    val note: String? = null,
)
