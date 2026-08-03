package com.macrotrack.app.data

import com.macrotrack.app.data.model.DailyLogStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import java.time.LocalDate

interface DayStatusRepository {
    suspend fun getStatus(date: LocalDate): DailyLogStatus?
    suspend fun setStatus(date: LocalDate, status: String, note: String? = null): DailyLogStatus
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
        val userId = requireUserId()
        val payload = DailyLogStatus(userId = userId, logDate = date.toString(), status = status, note = note)
        return client.postgrest.from("daily_log_status").upsert(payload) { select() }.decodeSingle<DailyLogStatus>()
    }
}
