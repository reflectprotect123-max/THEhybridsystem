package com.macrotrack.app.data

import com.macrotrack.app.data.model.NewTrendPoint
import com.macrotrack.app.data.model.TrendPoint
import com.macrotrack.app.domain.WeightSample
import com.macrotrack.app.domain.WeightTrendCalculator
import com.macrotrack.app.domain.round1
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
        val sinceDate = LocalDate.ofInstant(since, ZoneId.systemDefault())
        return client.postgrest.from("weight_trend_points").select {
            filter {
                eq("user_id", userId)
                gte("trend_date", sinceDate.toString())
            }
            order("trend_date", Order.ASCENDING)
        }.decodeList<TrendPoint>()
    }

    override suspend fun recomputeTrend(since: Instant): List<TrendPoint> {
        val userId = requireUserId()
        val zoneId = ZoneId.systemDefault()
        val entries = weightRepository.listEntries(since)
        val samples = entries.map { WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg) }
        val start = LocalDate.ofInstant(since, zoneId)
        val end = LocalDate.now(zoneId)
        val series = WeightTrendCalculator.dailyTrend(samples, start, end, zoneId)

        val payload = series.mapNotNull { (day, trendWeightKg) ->
            trendWeightKg?.let {
                NewTrendPoint(userId = userId, trendDate = day.toString(), trendWeightKg = round1(it))
            }
        }
        if (payload.isEmpty()) return emptyList()
        return client.postgrest.from("weight_trend_points").upsert(payload) { select() }.decodeList<TrendPoint>()
    }
}
