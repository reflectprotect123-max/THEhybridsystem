package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.custom_foods`. */
@Serializable
data class CustomFood(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") val servingQty: Double,
    @SerialName("serving_unit") val servingUnit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
)

/** Insert payload for a new custom food. `user_id` is filled in by the repository from the current session, never trusted from the caller. */
@Serializable
data class NewCustomFood(
    @SerialName("user_id") val userId: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") val servingQty: Double,
    @SerialName("serving_unit") val servingUnit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
)
