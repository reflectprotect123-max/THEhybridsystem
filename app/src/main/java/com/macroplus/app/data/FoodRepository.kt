package com.macroplus.app.data

import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodServing
import com.macroplus.app.domain.SearchPatterns
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
        val exactBarcode = barcode.trim()
        if (exactBarcode.isBlank()) return null
        return client.postgrest.from("foods").select {
            filter { eq("barcode", exactBarcode) }
            limit(1)
        }.decodeSingleOrNull<Food>()
    }

    override suspend fun search(query: String, limit: Int): List<Food> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        require(limit > 0) { "limit must be greater than 0" }
        val pattern = SearchPatterns.ilikePattern(cleanQuery)
        return client.postgrest.from("foods").select {
            filter {
                or {
                    ilike("name", pattern)
                    ilike("brand", pattern)
                }
            }
            order("name", Order.ASCENDING)
            limit(limit.coerceAtMost(100).toLong())
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
