package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors `public.foods` in supabase/migrations/001_macro_foundation.sql.
 * calories/protein_g/carbs_g/fat_g are the amounts for `servingQty` of
 * `servingUnit` on THIS row — not per `nutritionBasisQty`/`nutritionBasisUnit`,
 * which only records what the original source's denominator was (see
 * import_openfoodfacts.py's make_row: it scales macros to serving_qty before
 * writing the row). Scale off servingQty/servingUnit, not the basis fields.
 */
@Serializable
data class Food(
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") val servingQty: Double,
    @SerialName("serving_unit") val servingUnit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    val source: String,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("nutrition_basis_qty") val nutritionBasisQty: Double,
    @SerialName("nutrition_basis_unit") val nutritionBasisUnit: String,
    @SerialName("serving_size_text") val servingSizeText: String? = null,
)

/** Mirrors `public.food_servings`. */
@Serializable
data class FoodServing(
    val id: String,
    @SerialName("food_id") val foodId: String,
    val label: String,
    val quantity: Double,
    val unit: String,
    val grams: Double? = null,
    val millilitres: Double? = null,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("sort_order") val sortOrder: Int = 0,
)
