package com.macrotrack.app.data

import com.macrotrack.app.data.model.NewRecipe
import com.macrotrack.app.data.model.NewRecipeItem
import com.macrotrack.app.data.model.Recipe
import com.macrotrack.app.data.model.RecipeItem
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface RecipeRepository {
    suspend fun list(): List<Recipe>
    suspend fun create(name: String, description: String?, instructions: String?, servings: Double): Recipe
    suspend fun addItem(recipeId: String, foodId: String?, customFoodId: String?, quantity: Double, unit: String, sortOrder: Int): RecipeItem
    suspend fun getItems(recipeId: String): List<RecipeItem>
    suspend fun getById(id: String): Recipe?
}

class SupabaseRecipeRepository(private val client: SupabaseClient) : RecipeRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("RecipeRepository used before a user session exists.")
    }

    override suspend fun list(): List<Recipe> {
        val userId = requireUserId()
        return client.postgrest.from("recipes").select {
            filter { eq("user_id", userId) }
            order("name", Order.ASCENDING)
        }.decodeList<Recipe>()
    }

    override suspend fun create(name: String, description: String?, instructions: String?, servings: Double): Recipe {
        require(servings > 0) { "servings must be > 0 (matches the recipes.servings > 0 check constraint)" }
        val payload = NewRecipe(
            userId = requireUserId(),
            name = name,
            description = description,
            instructions = instructions,
            servings = servings,
        )
        return client.postgrest.from("recipes").insert(payload) { select() }.decodeSingle<Recipe>()
    }

    override suspend fun addItem(
        recipeId: String,
        foodId: String?,
        customFoodId: String?,
        quantity: Double,
        unit: String,
        sortOrder: Int,
    ): RecipeItem {
        require((foodId != null) != (customFoodId != null)) {
            "Exactly one of foodId or customFoodId must be set (matches the recipe_items check constraint)"
        }
        val payload = NewRecipeItem(
            recipeId = recipeId,
            foodId = foodId,
            customFoodId = customFoodId,
            quantity = quantity,
            unit = unit,
            sortOrder = sortOrder,
        )
        return client.postgrest.from("recipe_items").insert(payload) { select() }.decodeSingle<RecipeItem>()
    }

    override suspend fun getItems(recipeId: String): List<RecipeItem> {
        return client.postgrest.from("recipe_items").select {
            filter { eq("recipe_id", recipeId) }
            order("sort_order", Order.ASCENDING)
        }.decodeList<RecipeItem>()
    }

    override suspend fun getById(id: String): Recipe? {
        return client.postgrest.from("recipes").select {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull<Recipe>()
    }
}
