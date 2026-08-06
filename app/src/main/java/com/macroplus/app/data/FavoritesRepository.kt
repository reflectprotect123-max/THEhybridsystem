package com.macroplus.app.data

import com.macroplus.app.data.model.FoodFavorite
import com.macroplus.app.data.model.NewFoodFavorite
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface FavoritesRepository {
    suspend fun list(): List<FoodFavorite>
    suspend fun addFood(foodId: String)
    suspend fun removeFood(foodId: String)
    suspend fun addCustomFood(customFoodId: String)
    suspend fun removeCustomFood(customFoodId: String)
    suspend fun addRecipe(recipeId: String)
    suspend fun removeRecipe(recipeId: String)
}

class SupabaseFavoritesRepository(private val client: SupabaseClient) : FavoritesRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("FavoritesRepository used before a user session exists.")
    }

    override suspend fun list(): List<FoodFavorite> {
        val userId = requireUserId()
        return client.postgrest.from("food_favorites").select {
            filter { eq("user_id", userId) }
            order("sort_order", Order.ASCENDING)
        }.decodeList<FoodFavorite>()
    }

    override suspend fun addFood(foodId: String) {
        val payload = NewFoodFavorite(userId = requireUserId(), foodId = foodId)
        client.postgrest.from("food_favorites").insert(payload)
    }

    override suspend fun removeFood(foodId: String) {
        val userId = requireUserId()
        client.postgrest.from("food_favorites").delete {
            filter {
                eq("user_id", userId)
                eq("food_id", foodId)
            }
        }
    }

    override suspend fun addCustomFood(customFoodId: String) {
        val payload = NewFoodFavorite(userId = requireUserId(), customFoodId = customFoodId)
        client.postgrest.from("food_favorites").insert(payload)
    }

    override suspend fun removeCustomFood(customFoodId: String) {
        val userId = requireUserId()
        client.postgrest.from("food_favorites").delete {
            filter {
                eq("user_id", userId)
                eq("custom_food_id", customFoodId)
            }
        }
    }

    override suspend fun addRecipe(recipeId: String) {
        val payload = NewFoodFavorite(userId = requireUserId(), recipeId = recipeId)
        client.postgrest.from("food_favorites").insert(payload)
    }

    override suspend fun removeRecipe(recipeId: String) {
        val userId = requireUserId()
        client.postgrest.from("food_favorites").delete {
            filter {
                eq("user_id", userId)
                eq("recipe_id", recipeId)
            }
        }
    }
}
