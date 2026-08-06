package com.macroplus.app.data.model

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
 * `id`/`created_at` are server-generated and omitted (never applicable to
 * an update); `program_id` is included explicitly so the check-in remains
 * linked to the active goal that produced it.
 *
 * Every other nullable column here is a REQUIRED constructor parameter --
 * deliberately not defaulted to `null`. This table is written via `upsert`,
 * and a value can legitimately go from a real number back to `null` across
 * recomputes (e.g. a "ready" week's `proposed_calories` must clear to
 * `null` if the next recompute is "held"). kotlinx.serialization's
 * `encodeDefaults = false` omits a field left at its default value from the
 * JSON body entirely; PostgREST's upsert only updates columns present in
 * the body, so a defaulted-and-omitted field would leave a stale prior
 * value in place forever instead of clearing it. `resolvedAt` is the same
 * story: recomputing a previously-accepted/declined week must clear
 * `resolved_at` back to `null`, not silently leave the old timestamp next
 * to a fresh `pending`/`held` status.
 */
@Serializable
data class NewCheckIn(
    @SerialName("user_id") val userId: String,
    @SerialName("program_id") val programId: String?,
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    val status: String,
    @SerialName("previous_expenditure_kcal") val previousExpenditureKcal: Double?,
    @SerialName("observed_expenditure_kcal") val observedExpenditureKcal: Double?,
    @SerialName("proposed_expenditure_kcal") val proposedExpenditureKcal: Double?,
    @SerialName("proposed_calories") val proposedCalories: Double?,
    @SerialName("proposed_protein_g") val proposedProteinG: Double?,
    @SerialName("proposed_carbs_g") val proposedCarbsG: Double?,
    @SerialName("proposed_fat_g") val proposedFatG: Double?,
    val modules: JsonArray,
    val explanation: String,
    @SerialName("resolved_at") val resolvedAt: String?,
)
