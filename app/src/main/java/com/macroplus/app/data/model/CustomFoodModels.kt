package com.macroplus.app.data.model

import com.macroplus.app.domain.Scalable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Mirrors `public.custom_foods`. */
@Serializable
data class CustomFood(
    override val id: String,
    @SerialName("user_id") val userId: String,
    override val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") override val servingQty: Double,
    @SerialName("serving_unit") override val servingUnit: String,
    override val calories: Double,
    @SerialName("protein_g") override val proteinG: Double,
    @SerialName("carbs_g") override val carbsG: Double,
    @SerialName("fat_g") override val fatG: Double,
    val nutrients: JsonObject = buildJsonObject { },
) : Scalable

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
