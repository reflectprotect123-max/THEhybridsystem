package com.macrotrack.app.data

import com.macrotrack.app.data.model.CheckInModuleDto
import com.macrotrack.app.data.model.NewCheckIn
import com.macrotrack.app.data.model.PersistedCheckIn
import com.macrotrack.app.domain.CheckInResult
import com.macrotrack.app.domain.EngineConfig
import com.macrotrack.app.domain.weeklyCheckIn
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

interface CheckInRepository {
    suspend fun getCheckIn(weekStart: LocalDate): PersistedCheckIn?
    suspend fun recomputeCheckIn(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        targetRateKgPerWeek: Double,
        proteinGPerKg: Double = EngineConfig().defaultProteinGPerKg,
        fatGPerKg: Double = EngineConfig().defaultFatGPerKg,
    ): CheckInResult
    suspend fun resolve(weekStart: LocalDate, accepted: Boolean): PersistedCheckIn
}

class SupabaseCheckInRepository(
    private val client: SupabaseClient,
    private val expenditureRepository: ExpenditureRepository,
    private val weightRepository: WeightRepository,
) : CheckInRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("CheckInRepository used before a user session exists.")
    }

    override suspend fun getCheckIn(weekStart: LocalDate): PersistedCheckIn? {
        val userId = requireUserId()
        return client.postgrest.from("weekly_check_ins").select {
            filter {
                eq("user_id", userId)
                eq("week_start", weekStart.toString())
            }
            limit(1)
        }.decodeSingleOrNull<PersistedCheckIn>()
    }

    override suspend fun recomputeCheckIn(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        targetRateKgPerWeek: Double,
        proteinGPerKg: Double,
        fatGPerKg: Double,
    ): CheckInResult {
        val userId = requireUserId()

        val weightEntries = weightRepository.listEntries(Instant.EPOCH)
        val latestWeighIn = weightEntries.maxByOrNull { OffsetDateTime.parse(it.measuredAt).toInstant() }
            ?: error("recomputeCheckIn requires at least one logged weigh-in")
        val bodyWeightKg = latestWeighIn.weightKg

        val (records, previousExpenditureKcal) = expenditureRepository.loadRecords()

        val result = weeklyCheckIn(
            records = records,
            previousExpenditureKcal = previousExpenditureKcal,
            bodyWeightKg = bodyWeightKg,
            targetRateKgPerWeek = targetRateKgPerWeek,
            proteinGPerKg = proteinGPerKg,
            fatGPerKg = fatGPerKg,
            config = EngineConfig(),
        )

        val status = when (result.status) {
            "ready" -> "pending"
            else -> result.status // "held" already matches the schema's vocabulary
        }
        // Single-sourced through CheckInModuleDto (rather than hand-building
        // {"key":...,"action":...} JSON objects) so the decode side
        // (PersistedCheckIn.modules: List<CheckInModuleDto>) and this write
        // side can never silently disagree on field names.
        val modules = Json.encodeToJsonElement(result.modules.map { CheckInModuleDto(key = it.key, action = it.action) }).jsonArray
        val payload = NewCheckIn(
            userId = userId,
            weekStart = weekStart.toString(),
            weekEnd = weekEnd.toString(),
            status = status,
            previousExpenditureKcal = previousExpenditureKcal,
            // rawEstimateKcal, not estimateKcal -- on a holding result,
            // estimateKcal is the CARRIED-FORWARD anchor (see
            // AdaptiveEngine.estimateExpenditure's holding branches), not
            // anything actually observed this week. rawEstimateKcal is null
            // on exactly the same paths where there is nothing real to
            // report, which is what "observed" should mean here.
            observedExpenditureKcal = result.estimate.rawEstimateKcal,
            proposedExpenditureKcal = result.targets?.calories,
            proposedCalories = result.targets?.calories,
            proposedProteinG = result.targets?.proteinG,
            proposedCarbsG = result.targets?.carbsG,
            proposedFatG = result.targets?.fatG,
            modules = modules,
            explanation = result.explanation,
            // Explicitly null (not omitted) -- a week that was previously
            // accepted/declined and is now being recomputed must have its
            // resolved_at cleared, not left stamped next to a fresh
            // pending/held status.
            resolvedAt = null,
        )
        client.postgrest.from("weekly_check_ins").upsert(payload) { select() }.decodeSingle<PersistedCheckIn>()

        return result
    }

    override suspend fun resolve(weekStart: LocalDate, accepted: Boolean): PersistedCheckIn {
        val userId = requireUserId()
        val existing = getCheckIn(weekStart)
            ?: error("No check-in found for week_start=$weekStart")
        require(existing.status == "pending") {
            "Only a pending check-in can be resolved, got status='${existing.status}' for week_start=$weekStart"
        }
        return client.postgrest.from("weekly_check_ins").update({
            set("status", if (accepted) "accepted" else "declined")
            set("resolved_at", Instant.now().toString())
        }) {
            filter {
                eq("user_id", userId)
                eq("week_start", weekStart.toString())
                // Compare-and-set: without this, two concurrent resolves (or
                // a stale UI) could both pass the require() above and the
                // second would silently overwrite the first's outcome. A
                // loser here matches zero rows and decodeSingle throws
                // loudly instead.
                eq("status", "pending")
            }
            select()
        }.decodeSingle<PersistedCheckIn>()
    }
}
