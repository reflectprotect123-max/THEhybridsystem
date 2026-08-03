package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Mirrors `public.expenditure_estimates`. */
@Serializable
data class PersistedExpenditureEstimate(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("window_start") val windowStart: String,
    @SerialName("window_end") val windowEnd: String,
    @SerialName("estimate_kcal") val estimateKcal: Double,
    @SerialName("previous_estimate_kcal") val previousEstimateKcal: Double? = null,
    @SerialName("raw_estimate_kcal") val rawEstimateKcal: Double? = null,
    @SerialName("trend_slope_kg_per_week") val trendSlopeKgPerWeek: Double? = null,
    @SerialName("nutrition_days") val nutritionDays: Int,
    @SerialName("weight_days") val weightDays: Int,
    val confidence: String,
    val state: String,
    val method: String,
    val inputs: JsonObject,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Insert payload for a new expenditure estimate. `user_id` is filled in by
 * the repository from the current session, never trusted from the caller.
 * `id`/`created_at` are server-generated and `method` is left to the
 * schema's own default -- all three are omitted here. `inputs` has no
 * default: `ExpenditureEstimate.explanation` (no persisted column of its
 * own) is stored as `{"explanation": "..."}`, and this field must always be
 * transmitted, so it is a required constructor parameter rather than a
 * defaulted one (kotlinx.serialization's `encodeDefaults = false` would
 * silently drop a defaulted value).
 */
@Serializable
data class NewExpenditureEstimate(
    @SerialName("user_id") val userId: String,
    @SerialName("window_start") val windowStart: String,
    @SerialName("window_end") val windowEnd: String,
    @SerialName("estimate_kcal") val estimateKcal: Double,
    @SerialName("previous_estimate_kcal") val previousEstimateKcal: Double? = null,
    @SerialName("raw_estimate_kcal") val rawEstimateKcal: Double? = null,
    @SerialName("trend_slope_kg_per_week") val trendSlopeKgPerWeek: Double? = null,
    @SerialName("nutrition_days") val nutritionDays: Int,
    @SerialName("weight_days") val weightDays: Int,
    val confidence: String,
    val state: String,
    val inputs: JsonObject,
)
