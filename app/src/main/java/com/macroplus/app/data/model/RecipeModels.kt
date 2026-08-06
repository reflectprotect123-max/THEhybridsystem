package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.recipes`. */
@Serializable
data class Recipe(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val servings: Double,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class NewRecipe(
    @SerialName("user_id") val userId: String,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val servings: Double,
)

/**
 * Mirrors `public.recipe_items`. The DB check constraint requires exactly
 * one of `food_id`/`custom_food_id` to be set — this is enforced by the
 * database, not re-validated here, so a bad insert fails loudly with a
 * Postgrest error instead of silently storing an invalid row.
 */
@Serializable
data class RecipeItem(
    val id: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    val quantity: Double,
    val unit: String,
    @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
data class NewRecipeItem(
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    val quantity: Double,
    val unit: String,
    @SerialName("sort_order") val sortOrder: Int,
)
