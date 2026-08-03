package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raw row shape selected from `food_log_entries` for the recent-foods query. */
@Serializable
data class RecentLogEntryRow(
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: String,
)
