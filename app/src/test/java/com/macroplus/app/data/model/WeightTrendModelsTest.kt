package com.macroplus.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightTrendModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesATrendPointRow() {
        val row = """
            {
              "user_id": "user-1",
              "trend_date": "2026-08-03",
              "trend_weight_kg": 81.4,
              "method": "ewma_reference",
              "source_window_days": 14,
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val point = json.decodeFromString(TrendPoint.serializer(), row)

        assertEquals("user-1", point.userId)
        assertEquals("2026-08-03", point.trendDate)
        assertEquals(81.4, point.trendWeightKg, 0.001)
        assertEquals("ewma_reference", point.method)
        assertEquals(14, point.sourceWindowDays)
        assertEquals("2026-08-03T06:30:05Z", point.createdAt)
    }

    @Test
    fun encodesANewTrendPointPayloadWithAnExplicitNonDefaultMethod() {
        val payload = NewTrendPoint(
            userId = "user-1",
            trendDate = "2026-08-03",
            trendWeightKg = 81.4,
            method = "manual_override",
        )

        val encoded = json.encodeToString(NewTrendPoint.serializer(), payload)

        assertEquals(true, encoded.contains("\"trend_weight_kg\":81.4"))
        assertEquals(true, encoded.contains("\"method\":\"manual_override\""))
        assertEquals(false, encoded.contains("\"created_at\""))
    }
}
