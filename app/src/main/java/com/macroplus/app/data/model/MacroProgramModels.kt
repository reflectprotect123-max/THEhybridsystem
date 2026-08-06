package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.macro_programs`. */
@Serializable
data class MacroProgram(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val mode: String,
    val goal: String,
    @SerialName("target_rate_kg_per_week") val targetRateKgPerWeek: Double,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("weekly_calorie_budget") val weeklyCalorieBudget: Double? = null,
    @SerialName("protein_preference") val proteinPreference: String? = null,
    @SerialName("fat_preference") val fatPreference: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Insert payload for a new user-owned active macro program. */
@Serializable
data class NewMacroProgram(
    @SerialName("user_id") val userId: String,
    val name: String,
    val mode: String,
    val goal: String,
    @SerialName("target_rate_kg_per_week") val targetRateKgPerWeek: Double,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("weekly_calorie_budget") val weeklyCalorieBudget: Double? = null,
    @SerialName("protein_preference") val proteinPreference: String? = null,
    @SerialName("fat_preference") val fatPreference: String? = null,
    val status: String = "active",
)

/** Mirrors `public.macro_program_days`. */
@Serializable
data class MacroProgramDay(
    @SerialName("program_id") val programId: String,
    @SerialName("target_date") val targetDate: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    val source: String,
    @SerialName("created_at") val createdAt: String,
)

/** Insert/upsert payload for a concrete day target. */
@Serializable
data class NewMacroProgramDay(
    @SerialName("program_id") val programId: String,
    @SerialName("target_date") val targetDate: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    /** Required so the persisted row records why the target exists. */
    val source: String,
)
