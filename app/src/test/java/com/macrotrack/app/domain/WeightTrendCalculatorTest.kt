package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WeightTrendCalculatorTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun averageByLocalDayCombiniesSameDayDuplicatesByAveraging() {
        val samples = listOf(
            WeightSample(Instant.parse("2026-08-01T06:00:00Z"), 80.0),
            WeightSample(Instant.parse("2026-08-01T18:00:00Z"), 82.0),
        )

        val result = WeightTrendCalculator.averageByLocalDay(samples, zone)

        assertEquals(1, result.size)
        assertEquals(81.0, result[LocalDate.of(2026, 8, 1)]!!, 0.0001)
    }

    @Test
    fun averageByLocalDayPreservesDifferentDaysWithoutCollapsing() {
        val samples = listOf(
            WeightSample(Instant.parse("2026-08-01T06:00:00Z"), 80.0),
            WeightSample(Instant.parse("2026-08-02T06:00:00Z"), 82.0),
            WeightSample(Instant.parse("2026-08-03T06:00:00Z"), 84.0),
        )

        val result = WeightTrendCalculator.averageByLocalDay(samples, zone)

        assertEquals(3, result.size)
        assertEquals(80.0, result[LocalDate.of(2026, 8, 1)]!!, 0.0001)
        assertEquals(82.0, result[LocalDate.of(2026, 8, 2)]!!, 0.0001)
        assertEquals(84.0, result[LocalDate.of(2026, 8, 3)]!!, 0.0001)
    }

    @Test
    fun averageByLocalDayWithEmptyInputReturnsEmptyMap() {
        val result = WeightTrendCalculator.averageByLocalDay(emptyList(), zone)
        assertEquals(0, result.size)
    }

    @Test
    fun dailyTrendThrowsWhenEndIsBeforeStart() {
        val start = LocalDate.of(2026, 8, 3)
        val end = LocalDate.of(2026, 8, 1)

        try {
            WeightTrendCalculator.dailyTrend(emptyList(), start, end, zone)
            fail("Expected IllegalArgumentException but no exception was thrown")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun emptyInputProducesAllNullDays() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 3)

        val result = WeightTrendCalculator.dailyTrend(emptyList(), start, end, zone)

        assertEquals(listOf(start, start.plusDays(1), end), result.map { it.first })
        assertEquals(listOf(null, null, null), result.map { it.second })
    }

    @Test
    fun singleEntrySetsTrendFromThatDayOnward() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 3)
        val samples = listOf(WeightSample(Instant.parse("2026-08-02T06:00:00Z"), 80.0))

        val result = WeightTrendCalculator.dailyTrend(samples, start, end, zone)

        assertNull(result[0].second)
        assertEquals(80.0, result[1].second!!, 0.0001)
        assertEquals(80.0, result[2].second!!, 0.0001)
    }

    @Test
    fun gapDayCarriesThePreviousTrendForward() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 4)
        val samples = listOf(
            WeightSample(Instant.parse("2026-08-01T06:00:00Z"), 80.0),
            // 2026-08-02 has no weigh-in -- a gap day
            WeightSample(Instant.parse("2026-08-03T06:00:00Z"), 81.0),
            WeightSample(Instant.parse("2026-08-04T06:00:00Z"), 79.0),
        )

        val result = WeightTrendCalculator.dailyTrend(samples, start, end, zone, alpha = 0.20)

        // AdaptiveEngine.weightTrend only recomputes on a non-null input day; on a
        // null (gap) day it returns whatever `previous` already holds unchanged --
        // i.e. the gap day's own trend equals the prior day's trend, not null.
        val expectedDay1 = 80.0
        val expectedDay2 = expectedDay1 // gap day carries day 1's trend forward
        val expectedDay3 = 0.20 * 81.0 + 0.80 * expectedDay2
        val expectedDay4 = 0.20 * 79.0 + 0.80 * expectedDay3

        assertEquals(expectedDay1, result[0].second!!, 0.0001)
        assertEquals(expectedDay2, result[1].second!!, 0.0001)
        assertEquals(expectedDay3, result[2].second!!, 0.0001)
        assertEquals(expectedDay4, result[3].second!!, 0.0001)
    }

    @Test
    fun sameDayDuplicatesAreAveragedBeforeFeedingTheTrend() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 1)
        val samples = listOf(
            WeightSample(Instant.parse("2026-08-01T06:00:00Z"), 80.0),
            WeightSample(Instant.parse("2026-08-01T18:00:00Z"), 82.0),
        )

        val result = WeightTrendCalculator.dailyTrend(samples, start, end, zone)

        assertEquals(81.0, result[0].second!!, 0.0001)
    }
}
