package com.macroplus.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenditureEstimateModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAPersistedExpenditureEstimateRow() {
        val row = """
            {
              "id": "estimate-1",
              "user_id": "user-1",
              "window_start": "2026-07-01",
              "window_end": "2026-08-03",
              "estimate_kcal": 2450.0,
              "previous_estimate_kcal": 2400.0,
              "raw_estimate_kcal": 2460.5,
              "trend_slope_kg_per_week": -0.3,
              "nutrition_days": 12,
              "weight_days": 5,
              "confidence": "medium",
              "state": "updating",
              "method": "intake_minus_trend_energy",
              "inputs": {"explanation": "Expenditure updated from logged intake and smoothed weight trend."},
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val estimate = json.decodeFromString(PersistedExpenditureEstimate.serializer(), row)

        assertEquals("estimate-1", estimate.id)
        assertEquals("user-1", estimate.userId)
        assertEquals("2026-07-01", estimate.windowStart)
        assertEquals("2026-08-03", estimate.windowEnd)
        assertEquals(2450.0, estimate.estimateKcal, 0.001)
        assertEquals(2400.0, estimate.previousEstimateKcal!!, 0.001)
        assertEquals(-0.3, estimate.trendSlopeKgPerWeek!!, 0.001)
        assertEquals(12, estimate.nutritionDays)
        assertEquals(5, estimate.weightDays)
        assertEquals("medium", estimate.confidence)
        assertEquals("updating", estimate.state)
        assertEquals("intake_minus_trend_energy", estimate.method)
        assertEquals(
            "Expenditure updated from logged intake and smoothed weight trend.",
            estimate.inputs["explanation"]!!.toString().trim('"'),
        )
        assertEquals("2026-08-03T06:30:05Z", estimate.createdAt)
    }

    @Test
    fun decodesAHoldingRowWithNullOptionalFields() {
        val row = """
            {
              "id": "estimate-2",
              "user_id": "user-1",
              "window_start": "2026-07-01",
              "window_end": "2026-08-03",
              "estimate_kcal": 2400.0,
              "previous_estimate_kcal": null,
              "raw_estimate_kcal": null,
              "trend_slope_kg_per_week": null,
              "nutrition_days": 2,
              "weight_days": 1,
              "confidence": "low",
              "state": "holding",
              "method": "intake_minus_trend_energy",
              "inputs": {"explanation": "Nutrition logging is below the 6-of-7-day update gate."},
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val estimate = json.decodeFromString(PersistedExpenditureEstimate.serializer(), row)

        assertNull(estimate.previousEstimateKcal)
        assertNull(estimate.rawEstimateKcal)
        assertNull(estimate.trendSlopeKgPerWeek)
    }

    @Test
    fun encodesANewExpenditureEstimatePayloadIncludingInputsAndOmittingIdAndCreatedAt() {
        val payload = NewExpenditureEstimate(
            userId = "user-1",
            windowStart = "2026-07-01",
            windowEnd = "2026-08-03",
            estimateKcal = 2450.0,
            // Required (non-defaulted) parameters: on an upsert they must be
            // transmitted as explicit nulls so a prior row's values are
            // cleared rather than silently retained. See the KDoc on
            // NewExpenditureEstimate.
            previousEstimateKcal = null,
            rawEstimateKcal = null,
            trendSlopeKgPerWeek = null,
            nutritionDays = 12,
            weightDays = 5,
            confidence = "medium",
            state = "updating",
            inputs = buildJsonObject { put("explanation", "Expenditure updated from logged intake and smoothed weight trend.") },
        )

        val encoded = json.encodeToString(NewExpenditureEstimate.serializer(), payload)

        // estimateKcal/nutritionDays/weightDays/confidence/state are required
        // (non-optional) fields, so they always encode regardless of
        // encodeDefaults -- safe to assert their presence.
        assertEquals(true, encoded.contains("\"estimate_kcal\":2450.0"))
        assertEquals(true, encoded.contains("\"nutrition_days\":12"))
        assertEquals(true, encoded.contains("\"confidence\":\"medium\""))
        assertEquals(true, encoded.contains("\"state\":\"updating\""))
        // inputs has no default value on NewExpenditureEstimate, so it always
        // encodes too, regardless of encodeDefaults.
        assertEquals(true, encoded.contains("Expenditure updated from logged intake"))
        // The three nullable numeric fields have no default either, so an
        // explicit null still encodes as an explicit JSON null. This is what
        // lets an upsert clear a previously-persisted value; if any of them
        // regained a `= null` default they would vanish from the body here.
        assertEquals(true, encoded.contains("\"previous_estimate_kcal\":null"))
        assertEquals(true, encoded.contains("\"raw_estimate_kcal\":null"))
        assertEquals(true, encoded.contains("\"trend_slope_kg_per_week\":null"))
        assertEquals(false, encoded.contains("\"id\""))
        assertEquals(false, encoded.contains("\"created_at\""))
        assertEquals(false, encoded.contains("\"method\""))
    }
}
