package com.macrotrack.app.data

import com.macrotrack.app.data.model.CustomFood
import com.macrotrack.app.data.model.NewCustomFood
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface CustomFoodRepository {
    suspend fun list(): List<CustomFood>
    suspend fun create(name: String, brand: String?, servingQty: Double, servingUnit: String, calories: Double, proteinG: Double, carbsG: Double, fatG: Double, barcode: String? = null): CustomFood
    suspend fun delete(id: String)
    suspend fun getById(id: String): CustomFood?
    /** Exact match only, scoped to the current user's own custom foods. A blank barcode never matches. */
    suspend fun findByBarcode(barcode: String): CustomFood?
}

class SupabaseCustomFoodRepository(private val client: SupabaseClient) : CustomFoodRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("CustomFoodRepository used before a user session exists.")
    }

    override suspend fun list(): List<CustomFood> {
        val userId = requireUserId()
        return client.postgrest.from("custom_foods").select {
            filter { eq("user_id", userId) }
            order("name", Order.ASCENDING)
        }.decodeList<CustomFood>()
    }

    override suspend fun create(
        name: String,
        brand: String?,
        servingQty: Double,
        servingUnit: String,
        calories: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        barcode: String?,
    ): CustomFood {
        require(name.isNotBlank()) { "Custom food name must not be blank" }
        require(servingQty.isFinite() && servingQty > 0) { "servingQty must be greater than 0" }
        require(servingUnit.isNotBlank()) { "servingUnit must not be blank" }
        require(listOf(calories, proteinG, carbsG, fatG).all { it.isFinite() && it >= 0 }) {
            "Custom food nutrition values must be finite and non-negative"
        }
        val payload = NewCustomFood(
            userId = requireUserId(),
            name = name,
            brand = brand,
            barcode = barcode,
            servingQty = servingQty,
            servingUnit = servingUnit,
            calories = calories,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
        )
        return client.postgrest.from("custom_foods").insert(payload) { select() }.decodeSingle<CustomFood>()
    }

    override suspend fun delete(id: String) {
        val userId = requireUserId()
        client.postgrest.from("custom_foods").delete {
            filter {
                eq("id", id)
                eq("user_id", userId)
            }
        }
    }

    override suspend fun getById(id: String): CustomFood? {
        return client.postgrest.from("custom_foods").select {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull<CustomFood>()
    }

    override suspend fun findByBarcode(barcode: String): CustomFood? {
        val exactBarcode = barcode.trim()
        if (exactBarcode.isBlank()) return null
        val userId = requireUserId()
        return client.postgrest.from("custom_foods").select {
            filter {
                eq("user_id", userId)
                eq("barcode", exactBarcode)
            }
            limit(1)
        }.decodeSingleOrNull<CustomFood>()
    }
}
