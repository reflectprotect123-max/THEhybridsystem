package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.food_favorites`. No `id` column — uniqueness is enforced by partial unique indexes in the migration. */
@Serializable
data class FoodFavorite(
    @SerialName("user_id") val userId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class NewFoodFavorite(
    @SerialName("user_id") val userId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
)
