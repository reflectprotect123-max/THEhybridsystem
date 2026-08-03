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
     * per call, but nothing about that call is tied to elapsed time. Calling
     * this repeatedly in one day (e.g. once per app open, or again after the
     * user fixes a logging gap) must not chain a fresh damping step onto the
     * previous call's already-damped output each time -- that would drift
     * toward the raw value purely from call count, defeating
     * docs/ADAPTIVE_ENGINE_CONTRACT.md's damping cap. Nor can same-day calls
     * simply be skipped: a `holding` result telling the user to fix a
     * logging gap must actually re-evaluate once they do, the same day
     * (CLAUDE.md: "missing-data holding is a valid state" -- it must react
     * when the missing data arrives, not freeze until midnight).
     *
     * So every call always recomputes, but the damping is always anchored to
     * the last GENUINE (non-same-day) estimate, never to a same-day row's
     * own already-damped output -- and a same-day row is replaced in place
     * rather than accumulated, since it represents "today's estimate", not
     * a new historical entry.
     */
    override suspend fun recomputeExpenditure(): ExpenditureEstimate {
        val userId = requireUserId()
        val zoneId = ZoneId.systemDefault()
        val earliestBound = LocalDate.of(1970, 1, 1)
        val today = LocalDate.now(zoneId)

        val previous = getLatestEstimate()
        val isPreviousFromToday = previous != null && previous.windowEnd == today.toString()
        val dampingAnchor = when {
            previous == null -> null
            isPreviousFromToday -> previous.previousEstimateKcal
            else -> previous.estimateKcal
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

        val estimate = AdaptiveEngine.estimateExpenditure(records, dampingAnchor, EngineConfig())

        val estimateKcal = estimate.estimateKcal
        val windowStart = estimate.windowStart
        val windowEnd = estimate.windowEnd
        if (estimateKcal != null && windowStart != null && windowEnd != null) {
            if (isPreviousFromToday) {
                // Replace today's row rather than accumulate a second one --
                // it represents "today's estimate", re-evaluated, not a new
                // historical entry. previous is non-null whenever
                // isPreviousFromToday is true.
                client.postgrest.from("expenditure_estimates").delete {
                    filter {
                        eq("user_id", userId)
                        eq("id", previous.id)
                    }
                }
            }
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
}
