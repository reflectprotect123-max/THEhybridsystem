package com.macroplus.app.data

import com.macroplus.app.data.model.MacroProgram
import com.macroplus.app.data.model.MacroProgramDay
import com.macroplus.app.data.model.NewMacroProgram
import com.macroplus.app.data.model.NewMacroProgramDay
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.OffsetDateTime

interface MacroProgramRepository {
    suspend fun getActive(): MacroProgram?
    suspend fun saveActive(targetRateKgPerWeek: Double, name: String = "My macro goal"): MacroProgram
    suspend fun getDayTarget(date: LocalDate, programId: String): MacroProgramDay?
    suspend fun saveDayTargets(
        programId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        calories: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): List<MacroProgramDay>
}

class SupabaseMacroProgramRepository(private val client: SupabaseClient) : MacroProgramRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("MacroProgramRepository used before a user session exists.")
    }

    override suspend fun getActive(): MacroProgram? {
        val userId = requireUserId()
        return client.postgrest.from("macro_programs").select {
            filter {
                eq("user_id", userId)
                eq("status", "active")
            }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull<MacroProgram>()
    }

    override suspend fun saveActive(targetRateKgPerWeek: Double, name: String): MacroProgram {
        require(targetRateKgPerWeek.isFinite()) {
            "targetRateKgPerWeek must be finite, got $targetRateKgPerWeek"
        }
        require(name.isNotBlank()) { "Macro program name must not be blank" }

        val userId = requireUserId()
        val goal = when {
            targetRateKgPerWeek < 0.0 -> "lose"
            targetRateKgPerWeek > 0.0 -> "gain"
            else -> "maintain"
        }
        val existing = getActive()
        if (existing != null && existing.targetRateKgPerWeek == targetRateKgPerWeek) {
            return existing
        }
        if (existing != null) {
            // A changed goal is a new program, not an edit in place. This
            // preserves the meaning of historical weekly_check_ins rows:
            // their program_id still describes the goal that produced them.
            client.postgrest.from("macro_programs").update({
                set("status", "paused")
                set("updated_at", OffsetDateTime.now().toString())
            }) {
                filter {
                    eq("id", existing.id)
                    eq("user_id", userId)
                    eq("status", "active")
                }
            }
        }
        return client.postgrest.from("macro_programs").insert(
            NewMacroProgram(
                userId = userId,
                name = name.trim(),
                mode = "manual",
                goal = goal,
                targetRateKgPerWeek = targetRateKgPerWeek,
                startDate = LocalDate.now().toString(),
            ),
        ) { select() }.decodeSingle<MacroProgram>()
    }

    override suspend fun getDayTarget(date: LocalDate, programId: String): MacroProgramDay? {
        require(programId.isNotBlank()) { "programId must not be blank" }
        // `macro_program_days` has no `user_id` column of its own -- RLS scopes
        // it through the parent program -- so the returned id is unused here.
        // The call is still required: it awaits auth initialisation. Without
        // it, a read issued before the session is restored returns an empty
        // result set, which is indistinguishable from "this day genuinely has
        // no target". CoachViewModel treats a null day target as a missing
        // target that needs repairing and fires a write path, so an auth
        // timing race would masquerade as real missing data. Failing loudly is
        // the correct behaviour, exactly as in every other function here.
        requireUserId()
        return client.postgrest.from("macro_program_days").select {
            filter {
                eq("program_id", programId)
                eq("target_date", date.toString())
            }
            limit(1)
        }.decodeSingleOrNull<MacroProgramDay>()
    }

    override suspend fun saveDayTargets(
        programId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        calories: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): List<MacroProgramDay> {
        require(programId.isNotBlank()) { "programId must not be blank" }
        require(!endDate.isBefore(startDate)) { "endDate must not be before startDate" }
        require(listOf(calories, proteinG, carbsG, fatG).all { it.isFinite() && it >= 0 }) {
            "Day targets must be finite and non-negative"
        }

        val userId = requireUserId()
        // RLS verifies that the program belongs to this user. Fetching the
        // active row first gives a clearer failure and prevents writing day
        // targets against a paused historical program by mistake.
        val activeProgram = getActive()
        require(activeProgram?.id == programId && activeProgram.userId == userId) {
            "Cannot save day targets for a program that is not the active user program"
        }

        val payload = generateSequence(startDate) { current ->
            current.plusDays(1).takeUnless { it.isAfter(endDate) }
        }.map { date ->
            NewMacroProgramDay(
                programId = programId,
                targetDate = date.toString(),
                calories = calories,
                proteinG = proteinG,
                carbsG = carbsG,
                fatG = fatG,
                source = "accepted_check_in",
            )
        }.toList()

        // Keep this bounded even though a normal check-in writes at most seven
        // rows; the repository contract must remain safe if a future caller
        // backfills a longer, explicitly bounded target range.
        return payload.chunked(500).flatMap { batch ->
            client.postgrest.from("macro_program_days").upsert(batch) {
                onConflict = "program_id,target_date"
                select()
            }.decodeList<MacroProgramDay>()
        }
    }
}
