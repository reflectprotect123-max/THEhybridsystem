package com.macrotrack.app.data

import com.macrotrack.app.data.model.DailyLogStatus
import com.macrotrack.app.data.model.DayStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

interface DayStatusRepository {
    suspend fun getStatus(date: LocalDate): DailyLogStatus?
    suspend fun setStatus(date: LocalDate, status: String, note: String? = null): DailyLogStatus
    suspend fun listStatuses(since: LocalDate): List<DailyLogStatus>
}

class SupabaseDayStatusRepository(private val client: SupabaseClient) : DayStatusRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("DayStatusRepository used before a user session exists.")
    }

    override suspend fun getStatus(date: LocalDate): DailyLogStatus? {
        val userId = requireUserId()
        return client.postgrest.from("daily_log_status").select {
            filter {
                eq("user_id", userId)
                eq("log_date", date.toString())
            }
            limit(1)
        }.decodeSingleOrNull<DailyLogStatus>()
    }

    override suspend fun setStatus(date: LocalDate, status: String, note: String?): DailyLogStatus {
        require(status in VALID_STATUSES) {
            "status must be one of $VALID_STATUSES, got '$status'"
        }
        val userId = requireUserId()
        val payload = DailyLogStatus(userId = userId, logDate = date.toString(), status = status, note = note)
        return client.postgrest.from("daily_log_status").upsert(payload) { select() }.decodeSingle<DailyLogStatus>()
    }

    override suspend fun listStatuses(since: LocalDate): List<DailyLogStatus> {
        val userId = requireUserId()
        return client.postgrest.from("daily_log_status").select {
            filter {
                eq("user_id", userId)
                gte("log_date", since.toString())
            }
            order("log_date", Order.ASCENDING)
        }.decodeList<DailyLogStatus>()
    }

    companion object {
        private val VALID_STATUSES = setOf(DayStatus.COMPLETE, DayStatus.PARTIAL, DayStatus.FASTED, DayStatus.UNLOGGED)
    }
}
