package com.macroplus.app.data

import com.macroplus.app.data.model.NewExpenditureEstimate
import com.macroplus.app.data.model.PersistedExpenditureEstimate
import com.macroplus.app.domain.AdaptiveEngine
import com.macroplus.app.domain.DailyRecord
import com.macroplus.app.domain.EngineConfig
import com.macroplus.app.domain.ExpenditureEstimate
import com.macroplus.app.domain.ExpenditureRecordAssembler
import com.macroplus.app.domain.WeightSample
import com.macroplus.app.domain.WeightTrendCalculator
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
    suspend fun loadRecords(): Pair<List<DailyRecord>, Double?>
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
            // `id` is a random uuid, not a sequence -- this tiebreaker only
            // makes a created_at tie deterministic (so retries/tests are
            // reproducible), it does not mean "and then by recency".
            order("id", Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull<PersistedExpenditureEstimate>()
    }

    override suspend fun loadRecords(): Pair<List<DailyRecord>, Double?> {
        return loadRecordsAt(LocalDate.now(ZoneId.systemDefault()))
    }

    private suspend fun loadRecordsAt(today: LocalDate): Pair<List<DailyRecord>, Double?> {
        val zoneId = ZoneId.systemDefault()
        val earliestBound = LocalDate.of(1970, 1, 1)

        val previous = getLatestEstimate()
        // "previous is from today" is only decidable this cheaply because
        // ExpenditureRecordAssembler.assemble is always called with
        // end = today (below), so estimateExpenditure's windowEnd is always
        // today's date whenever a row gets persisted. If that ever changes
        // (e.g. ending records at the last *logged* day instead), this
        // comparison silently stops detecting same-day recomputes and the
        // per-invocation damping-chain bug this whole function exists to
        // prevent would return undetected.
        val dampingAnchor = when {
            previous == null -> null
            previous.windowEnd == today.toString() -> previous.previousEstimateKcal
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

        return records to dampingAnchor
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
        val today = LocalDate.now(ZoneId.systemDefault())

        // Keep the date used for assembly, damping, and persistence identical.
        // This avoids a midnight boundary changing which row is treated as the
        // current estimate during one recompute.
        val (records, dampingAnchor) = loadRecordsAt(today)

        val estimate = AdaptiveEngine.estimateExpenditure(records, dampingAnchor, EngineConfig())

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
            // Migration 003 makes (user_id, window_end) unique. This is one
            // derived estimate per user/day; upsert makes refreshes atomic and
            // safely handles two refresh callers racing each other.
            client.postgrest.from("expenditure_estimates").upsert(payload) {
                onConflict = "user_id,window_end"
            }
        } else {
            // If today's history no longer supports a concrete estimate (for
            // example, the user deleted the last logged record), remove only
            // today's derived row so getLatestEstimate() cannot return stale
            // numbers that disagree with the fresh holding result.
            client.postgrest.from("expenditure_estimates").delete {
                filter {
                    eq("user_id", userId)
                    eq("window_end", today.toString())
                }
            }
        }

        return estimate
    }
}
