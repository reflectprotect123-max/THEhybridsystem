package com.macrotrack.app.data

import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.FoodServing
import com.macrotrack.app.domain.SearchPatterns
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface FoodRepository {
    /** Exact match only. A food with `barcode = NULL` never matches. */
    suspend fun findByBarcode(barcode: String): Food?
    suspend fun search(query: String, limit: Int = 20): List<Food>
    suspend fun getServings(foodId: String): List<FoodServing>
    suspend fun getById(id: String): Food?
}

class SupabaseFoodRepository(private val client: SupabaseClient) : FoodRepository {

    override suspend fun findByBarcode(barcode: String): Food? {
        if (barcode.isBlank()) return null
        return client.postgrest.from("foods").select {
            filter { eq("barcode", barcode) }
            limit(1)
        }.decodeSingleOrNull<Food>()
    }

    override suspend fun search(query: String, limit: Int): List<Food> {
        if (query.isBlank()) return emptyList()
        val pattern = SearchPatterns.ilikePattern(query)
        return client.postgrest.from("foods").select {
            filter {
                or {
                    ilike("name", pattern)
                    ilike("brand", pattern)
                }
            }
            order("name", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeList<Food>()
    }

    override suspend fun getServings(foodId: String): List<FoodServing> {
        return client.postgrest.from("food_servings").select {
            filter { eq("food_id", foodId) }
            order("sort_order", Order.ASCENDING)
        }.decodeList<FoodServing>()
    }

    override suspend fun getById(id: String): Food? {
        return client.postgrest.from("foods").select {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull<Food>()
    }
}
