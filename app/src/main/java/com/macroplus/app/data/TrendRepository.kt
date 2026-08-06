package com.macroplus.app.data

import com.macroplus.app.data.model.NewTrendPoint
import com.macroplus.app.data.model.TrendPoint
import com.macroplus.app.domain.WeightSample
import com.macroplus.app.domain.WeightTrendCalculator
import com.macroplus.app.domain.round1
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

interface TrendRepository {
    suspend fun listTrendPoints(since: Instant): List<TrendPoint>
    suspend fun recomputeTrend(since: Instant): List<TrendPoint>
}

class SupabaseTrendRepository(
    private val client: SupabaseClient,
    private val weightRepository: WeightRepository,
) : TrendRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("TrendRepository used before a user session exists.")
    }

    override suspend fun listTrendPoints(since: Instant): List<TrendPoint> {
        val userId = requireUserId()
        // Instant.atZone(zoneId).toLocalDate() -- NOT LocalDate.ofInstant, which
        // is a Java 9 java.time addition Android only ships from API 34; this
        // module's minSdk is 26 with no core-library desugaring.
        val sinceDate = since.atZone(ZoneId.systemDefault()).toLocalDate()
        return client.postgrest.from("weight_trend_points").select {
            filter {
                eq("user_id", userId)
                gte("trend_date", sinceDate.toString())
            }
            order("trend_date", Order.ASCENDING)
        }.decodeList<TrendPoint>()
    }

    /**
     * Recomputes the daily EWMA trend and upserts `[since, today]` into
     * `weight_trend_points`. The EWMA is always seeded from the user's
     * earliest ever weigh-in (fetched with no lower bound), never from
     * `since` -- seeding partway through a history would make the trend
     * value for a given `(user_id, trend_date)` depend on whatever `since`
     * happened to be passed on the call that last wrote it, which is not
     * deterministic. `since` only bounds which days get persisted/returned,
     * matching how `AdaptiveEngine.estimateExpenditure` computes its own
     * trend over the full history and only then filters to a window.
     *
     * Rows previously persisted in `[since, today]` that no longer have a
     * computed trend value (e.g. their only source weigh-in was deleted)
     * are removed rather than left stale.
     */
    override suspend fun recomputeTrend(since: Instant): List<TrendPoint> {
        val userId = requireUserId()
        val zoneId = ZoneId.systemDefault()
        val requestedStart = since.atZone(zoneId).toLocalDate()
        val today = LocalDate.now(zoneId)
        if (today.isBefore(requestedStart)) {
            // `since` is in the future relative to today -- nothing to compute.
            return emptyList()
        }

        val entries = weightRepository.listEntries(Instant.EPOCH)
        val samples = entries.map { WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg) }

        val payload = if (samples.isEmpty()) {
            emptyList()
        } else {
            val earliestDay = samples.minOf { it.measuredAt.atZone(zoneId).toLocalDate() }
            if (earliestDay.isAfter(today)) {
                // Every weigh-in is future-dated relative to `today` -- nothing
                // to compute yet; avoid dailyTrend's own end-before-start guard.
                emptyList()
            } else {
                val series = WeightTrendCalculator.dailyTrend(samples, earliestDay, today, zoneId)
                series
                    .filter { (day, _) -> !day.isBefore(requestedStart) }
                    .mapNotNull { (day, trendWeightKg) ->
                        trendWeightKg?.let {
                            NewTrendPoint(userId = userId, trendDate = day.toString(), trendWeightKg = round1(it))
                        }
                    }
            }
        }

        // `payload` is always a contiguous run from max(earliestDay, requestedStart)
        // through `today` -- AdaptiveEngine.weightTrend carries the last trend
        // value forward forever once seeded, so it never re-emits null once a
        // weigh-in has been seen. That means the only rows that can be stale in
        // [requestedStart, today] are a leading gap before payload's first date
        // (source weigh-ins before that date were deleted) or, if payload is
        // empty, the entire window (all source data gone). Clean up exactly
        // that range -- never rows the fresh payload is about to rewrite -- so
        // an unchanged day's `created_at` isn't reset on every recompute.
        //
        // The delete's two trend_date bounds are grouped inside `and { }`
        // rather than as two direct `filter{}` calls: postgrest-kt's top-level
        // request params are keyed by column and folded to their FIRST value
        // only (see Utils.kt's `mapToFirstValue`), so two direct `gte`/`lt`
        // calls on the same "trend_date" column would silently drop one of
        // them. `and { }`'s inner params are joined into one string instead,
        // so both bounds survive -- but only ONE top-level `and { }`/`or { }`
        // per filter{} block: a second one would overwrite the first the
        // same way (they're keyed "and"/"or", also folded to a first value).
        val firstPersistedDate = payload.firstOrNull()?.trendDate
        if (firstPersistedDate == null || firstPersistedDate != requestedStart.toString()) {
            client.postgrest.from("weight_trend_points").delete {
                filter {
                    eq("user_id", userId)
                    and {
                        gte("trend_date", requestedStart.toString())
                        if (firstPersistedDate != null) {
                            lt("trend_date", firstPersistedDate)
                        } else {
                            lte("trend_date", today.toString())
                        }
                    }
                }
            }
        }

        if (payload.isEmpty()) return emptyList()
        // CLAUDE.md rule #5: keep SQL batches at 500 rows or fewer. `payload`
        // is one row per day since the user's earliest weigh-in, which can
        // exceed 500 for a multi-year history.
        return payload.chunked(UPSERT_BATCH_SIZE).flatMap { batch ->
            client.postgrest.from("weight_trend_points").upsert(batch) { select() }.decodeList<TrendPoint>()
        }
    }

    companion object {
        private const val UPSERT_BATCH_SIZE = 500
    }
}
