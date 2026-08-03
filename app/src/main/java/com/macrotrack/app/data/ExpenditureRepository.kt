package com.macrotrack.app.data

import com.macrotrack.app.data.model.NewExpenditureEstimate
import com.macrotrack.app.data.model.PersistedExpenditureEstimate
import com.macrotrack.app.domain.AdaptiveEngine
import com.macrotrack.app.domain.EngineConfig
import com.macrotrack.app.domain.ExpenditureEstimate
import com.macrotrack.app.domain.ExpenditureRecordAssembler
import com.macrotrack.app.domain.WeightSample
import com.macrotrack.app.domain.WeightTrendCalculator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

interface ExpenditureRepository {
    suspend fun getLatestEstimate(): PersistedExpenditureEstimate?
    suspend fun recomputeExpenditure(): ExpenditureEstimate
}

class SupabaseExpenditureRepository(
    private val client: SupabaseClient,
    private val dayStatusRepository: DayStatusRepository,
    private val logRepository: LogRepository,
    private val weightRepository: WeightRepository,
) : ExpenditureRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("ExpenditureRepository used before a user session exists.")
    }

    override suspend fun getLatestEstimate(): PersistedExpenditureEstimate? {
        val userId = requireUserId()
        return client.postgrest.from("expenditure_estimates").select {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
            order("id", Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull<PersistedExpenditureEstimate>()
    }

    /**
     * Recomputes the current expenditure estimate from full logged history
     * and persists it, unless the engine had nothing concrete to report
     * (see this plan's Global Constraints for why that case is skipped
     * rather than given a sentinel). Always returns the engine's result,
     * whether or not a row was written.
     *
     * `AdaptiveEngine.estimateExpenditure` applies at most one damping step
     * per call, but nothing about that call is tied to elapsed time --
     * calling this repeatedly with unchanged data (e.g. once per app open)
     * would otherwise chain a fresh damping step onto the previous call's
     * result every time, silently defeating
     * docs/ADAPTIVE_ENGINE_CONTRACT.md's "limit the weekly update step" cap
     * within a single day. If the latest persisted estimate's window
     * already reaches today, there is nothing new to compute -- that
     * persisted estimate is returned unchanged instead of taking another
     * step toward the raw value.
     */
    override suspend fun recomputeExpenditure(): ExpenditureEstimate {
        val userId = requireUserId()
        val zoneId = ZoneId.systemDefault()
        val earliestBound = LocalDate.of(1970, 1, 1)
        val today = LocalDate.now(zoneId)

        val previous = getLatestEstimate()
        if (previous != null && previous.windowEnd == today.toString()) {
            return previous.toExpenditureEstimate()
        }

        val statuses = dayStatusRepository.listStatuses(earliestBound)
        val totals = logRepository.listDailyTotals(earliestBound)
        val weightEntries = weightRepository.listEntries(Instant.EPOCH)
        val weightSamples = weightEntries.map { WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg) }
        val weightByDay = WeightTrendCalculator.averageByLocalDay(weightSamples, zoneId)

        val allKnownDates = buildList {
            statuses.mapTo(this) { LocalDate.parse(it.logDate) }
            totals.mapTo(this) { LocalDate.parse(it.logDate) }
            addAll(weightByDay.keys)
        }
        val records = if (allKnownDates.isEmpty() || allKnownDates.min().isAfter(today)) {
            // Either nothing logged at all, or every known date is in the
            // future (e.g. a future-dated weigh-in) -- there is no real
            // history to assemble a [start, today] range from. Feeding
            // ExpenditureRecordAssembler a start after its end would trip
            // its own require(!end.isBefore(start)) guard.
            emptyList()
        } else {
            ExpenditureRecordAssembler.assemble(statuses, totals, weightByDay, allKnownDates.min(), today)
        }

        val estimate = AdaptiveEngine.estimateExpenditure(records, previous?.estimateKcal, EngineConfig())

        val estimateKcal = estimate.estimateKcal
        val windowStart = estimate.windowStart
        val windowEnd = estimate.windowEnd
        if (estimateKcal != null && windowStart != null && windowEnd != null) {
            val payload = NewExpenditureEstimate(
                userId = userId,
                windowStart = windowStart,
                windowEnd = windowEnd,
                estimateKcal = estimateKcal,
                previousEstimateKcal = estimate.previousEstimateKcal,
                rawEstimateKcal = estimate.rawEstimateKcal,
                trendSlopeKgPerWeek = estimate.trendSlopeKgPerWeek,
                nutritionDays = estimate.nutritionDays,
                weightDays = estimate.weightDays,
                confidence = estimate.confidence,
                state = estimate.state,
                inputs = buildJsonObject { put("explanation", estimate.explanation) },
            )
            client.postgrest.from("expenditure_estimates").insert(payload)
        }

        return estimate
    }

    /** Reconstructs the domain result from a persisted row, for the already-computed-today short-circuit above. */
    private fun PersistedExpenditureEstimate.toExpenditureEstimate(): ExpenditureEstimate = ExpenditureEstimate(
        state = state,
        confidence = confidence,
        estimateKcal = estimateKcal,
        rawEstimateKcal = rawEstimateKcal,
        previousEstimateKcal = previousEstimateKcal,
        trendSlopeKgPerWeek = trendSlopeKgPerWeek,
        nutritionDays = nutritionDays,
        weightDays = weightDays,
        windowStart = windowStart,
        windowEnd = windowEnd,
        explanation = (inputs["explanation"] as? JsonPrimitive)?.content ?: "",
    )
}
