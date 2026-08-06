package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.weight_trend_points`. Primary key is `(user_id, trend_date)`. */
@Serializable
data class TrendPoint(
    @SerialName("user_id") val userId: String,
    @SerialName("trend_date") val trendDate: String,
    @SerialName("trend_weight_kg") val trendWeightKg: Double,
    val method: String,
    @SerialName("source_window_days") val sourceWindowDays: Int,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Upsert payload for a recomputed trend point. `user_id` is filled in by
 * the repository from the current session, never trusted from the caller.
 * `created_at` is server-generated and omitted here.
 */
@Serializable
data class NewTrendPoint(
    @SerialName("user_id") val userId: String,
    @SerialName("trend_date") val trendDate: String,
    @SerialName("trend_weight_kg") val trendWeightKg: Double,
    val method: String = "ewma_reference",
    @SerialName("source_window_days") val sourceWindowDays: Int = 14,
)
