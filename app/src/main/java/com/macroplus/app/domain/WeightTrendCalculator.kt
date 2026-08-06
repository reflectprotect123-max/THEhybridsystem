package com.macroplus.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One raw weigh-in, already resolved to an absolute instant. */
data class WeightSample(val measuredAt: Instant, val weightKg: Double)

/**
 * Bridges sparse, instant-keyed weigh-ins into the dense, gap-null-carrying
 * per-local-day series `AdaptiveEngine.weightTrend` expects. Buckets by
 * `zoneId` (device-local, not UTC, to match `log_date`'s day-keying
 * elsewhere in this app), averages same-day duplicates, and leaves a day
 * with no weigh-in as `null` so the EWMA gap-carry-forward behavior
 * actually engages.
 */
object WeightTrendCalculator {

    /**
     * Groups multiple weigh-ins on the same local day and combines them by averaging.
     * This is the standard coaching convention for same-day duplicates.
     */
    internal fun averageByLocalDay(samples: List<WeightSample>, zoneId: ZoneId): Map<LocalDate, Double> =
        samples
            .groupBy { it.measuredAt.atZone(zoneId).toLocalDate() }
            .mapValues { (_, dayEntries) -> dayEntries.map { it.weightKg }.average() }

    fun dailyTrend(
        samples: List<WeightSample>,
        start: LocalDate,
        end: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        alpha: Double = EngineConfig().trendAlpha,
    ): List<Pair<LocalDate, Double?>> {
        require(!end.isBefore(start)) { "end must not be before start, got start=$start end=$end" }
        val averagedByDay = averageByLocalDay(samples, zoneId)

        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val denseSeries = days.map { averagedByDay[it] }
        val trend = AdaptiveEngine.weightTrend(denseSeries, alpha)
        return days.zip(trend)
    }
}
