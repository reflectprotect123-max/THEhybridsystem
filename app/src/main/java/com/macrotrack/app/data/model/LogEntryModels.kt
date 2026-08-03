package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Values `food_log_entries.entry_kind`'s DB check constraint allows — copied verbatim, not re-derived. */
object EntryKind {
    const val FOOD = "food"
    const val CUSTOM_FOOD = "custom_food"
    const val RECIPE = "recipe"
    const val QUICK_ADD = "quick_add"
}

/** Suggested `food_log_entries.meal` values. Not DB-enforced — the column is free text. */
object Meal {
    const val BREAKFAST = "breakfast"
    const val LUNCH = "lunch"
    const val DINNER = "dinner"
    const val SNACK = "snack"
    const val OTHER = "other"
}

/**
 * Mirrors `public.food_log_entries`. calories/protein_g/carbs_g/fat_g and
 * display_name are the SNAPSHOT taken at log time — never re-derived from
 * food_id/custom_food_id/recipe_id after the fact, so editing a food later
 * does not rewrite history (CLAUDE.md rule #2/#3).
 */
@Serializable
data class FoodLogEntry(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val meal: String,
    @SerialName("entry_kind") val entryKind: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    val quantity: Double,
    val unit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("display_name") val displayName: String,
    val notes: String? = null,
)

/** Insert payload. `user_id` is filled in by the repository from the session, never trusted from the caller. */
@Serializable
data class NewFoodLogEntry(
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val meal: String,
    @SerialName("entry_kind") val entryKind: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    val quantity: Double,
    val unit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("display_name") val displayName: String,
    val notes: String? = null,
)

/**
 * Mirrors the `public.daily_nutrition_totals` view. The view's own SQL
 * (`group by user_id, log_date`) means a date with zero entries simply has
 * no row — callers get `null` from `decodeSingleOrNull`, never a zeroed
 * DailyTotals. Do not construct a synthetic zero DailyTotals anywhere.
 */
@Serializable
data class DailyTotals(
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("entry_count") val entryCount: Int,
)
