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
}
