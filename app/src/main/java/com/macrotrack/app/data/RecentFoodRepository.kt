package com.macrotrack.app.data

import com.macrotrack.app.data.model.RecentLogEntryRow
import com.macrotrack.app.domain.RecentLogReference
import com.macrotrack.app.domain.dedupeRecentReferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

private const val FETCH_BUFFER = 50L

interface RecentFoodRepository {
    suspend fun getRecent(limit: Int = 10): List<RecentLogReference>
}

class SupabaseRecentFoodRepository(private val client: SupabaseClient) : RecentFoodRepository {

    override suspend fun getRecent(limit: Int): List<RecentLogReference> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: error("RecentFoodRepository used before a user session exists.")
        val rows = client.postgrest.from("food_log_entries").select {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
            limit(FETCH_BUFFER)
        }.decodeList<RecentLogEntryRow>()

        val references = rows.map {
            RecentLogReference(
                foodId = it.foodId,
                customFoodId = it.customFoodId,
                recipeId = it.recipeId,
                displayName = it.displayName,
                loggedAt = it.createdAt,
            )
        }
        return dedupeRecentReferences(references, limit)
    }
}
