package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/** Serializable mirror of the domain `CheckInModule` (WeeklyCheckIn.kt), for jsonb encoding. */
@Serializable
data class CheckInModuleDto(
    val key: String,
    val action: String,
)

/** Mirrors `public.weekly_check_ins`. */
@Serializable
data class PersistedCheckIn(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("program_id") val programId: String? = null,
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    val status: String,
    @SerialName("previous_expenditure_kcal") val previousExpenditureKcal: Double? = null,
    @SerialName("observed_expenditure_kcal") val observedExpenditureKcal: Double? = null,
    @SerialName("proposed_expenditure_kcal") val proposedExpenditureKcal: Double? = null,
    @SerialName("proposed_calories") val proposedCalories: Double? = null,
    @SerialName("proposed_protein_g") val proposedProteinG: Double? = null,
    @SerialName("proposed_carbs_g") val proposedCarbsG: Double? = null,
    @SerialName("proposed_fat_g") val proposedFatG: Double? = null,
    val modules: List<CheckInModuleDto>,
    val explanation: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

/**
 * Upsert payload for a recomputed check-in. `user_id` is filled in by the
 * repository from the current session, never trusted from the caller.
 * `id`/`created_at` are server-generated; `program_id` is always null in
 * this slice (`macro_programs` is out of scope); `resolved_at` is always
 * null on an initial recompute (only `CheckInRepository.resolve` sets it) --
 * all three are omitted here rather than defaulted, so kotlinx.serialization
 * never has a default value to silently drop on this side.
 */
@Serializable
data class NewCheckIn(
    @SerialName("user_id") val userId: String,
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    val status: String,
    @SerialName("previous_expenditure_kcal") val previousExpenditureKcal: Double? = null,
    @SerialName("observed_expenditure_kcal") val observedExpenditureKcal: Double? = null,
    @SerialName("proposed_expenditure_kcal") val proposedExpenditureKcal: Double? = null,
    @SerialName("proposed_calories") val proposedCalories: Double? = null,
    @SerialName("proposed_protein_g") val proposedProteinG: Double? = null,
    @SerialName("proposed_carbs_g") val proposedCarbsG: Double? = null,
    @SerialName("proposed_fat_g") val proposedFatG: Double? = null,
    val modules: JsonArray,
    val explanation: String,
)
