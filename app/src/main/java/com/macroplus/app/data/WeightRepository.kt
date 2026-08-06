package com.macroplus.app.data

import com.macroplus.app.data.model.NewWeightEntry
import com.macroplus.app.data.model.WeightEntry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant

interface WeightRepository {
    suspend fun listEntries(since: Instant): List<WeightEntry>
    suspend fun logWeight(measuredAt: Instant, weightKg: Double, source: String = "manual", note: String? = null): WeightEntry
    suspend fun deleteEntry(entryId: String)
}

class SupabaseWeightRepository(private val client: SupabaseClient) : WeightRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("WeightRepository used before a user session exists.")
    }

    override suspend fun listEntries(since: Instant): List<WeightEntry> {
        val userId = requireUserId()
        return client.postgrest.from("weight_entries").select {
            filter {
                eq("user_id", userId)
                gte("measured_at", since.toString())
            }
            order("measured_at", Order.ASCENDING)
        }.decodeList<WeightEntry>()
    }

    override suspend fun logWeight(measuredAt: Instant, weightKg: Double, source: String, note: String?): WeightEntry {
        require(weightKg in 20.0..500.0) { "weightKg must be between 20 and 500, got $weightKg" }
        val userId = requireUserId()
        val payload = NewWeightEntry(
            userId = userId,
            measuredAt = measuredAt.toString(),
            weightKg = weightKg,
            source = source,
            note = note,
        )
        return client.postgrest.from("weight_entries").insert(payload) { select() }.decodeSingle<WeightEntry>()
    }

    override suspend fun deleteEntry(entryId: String) {
        val userId = requireUserId()
        client.postgrest.from("weight_entries").delete {
            filter {
                eq("id", entryId)
                eq("user_id", userId)
            }
        }
    }
}
