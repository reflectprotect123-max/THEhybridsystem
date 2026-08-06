package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.weight_entries`. */
@Serializable
data class WeightEntry(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
    val source: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Insert payload for a new weight entry. `user_id` is filled in by the
 * repository from the current session, never trusted from the caller.
 * `id`/`created_at` are server-generated and omitted here.
 */
@Serializable
data class NewWeightEntry(
    @SerialName("user_id") val userId: String,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
    val source: String = "manual",
    val note: String? = null,
)
