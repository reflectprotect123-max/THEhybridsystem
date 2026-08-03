package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckInModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAPersistedCheckInRow() {
        val row = """
            {
              "id": "checkin-1",
              "user_id": "user-1",
              "program_id": null,
              "week_start": "2026-07-27",
              "week_end": "2026-08-02",
              "status": "pending",
              "previous_expenditure_kcal": 2400.0,
              "observed_expenditure_kcal": 2450.0,
              "proposed_expenditure_kcal": 2250.0,
              "proposed_calories": 2250.0,
              "proposed_protein_g": 160.0,
              "proposed_carbs_g": 200.0,
              "proposed_fat_g": 70.0,
              "modules": [],
              "explanation": "The next target uses observed expenditure and the signed goal rate.",
              "created_at": "2026-08-03T06:30:05Z",
              "resolved_at": null
            }
        """.trimIndent()

        val checkIn = json.decodeFromString(PersistedCheckIn.serializer(), row)

        assertEquals("checkin-1", checkIn.id)
        assertEquals("user-1", checkIn.userId)
        assertNull(checkIn.programId)
        assertEquals("2026-07-27", checkIn.weekStart)
        assertEquals("2026-08-02", checkIn.weekEnd)
        assertEquals("pending", checkIn.status)
        assertEquals(2400.0, checkIn.previousExpenditureKcal!!, 0.001)
        assertEquals(2250.0, checkIn.proposedCalories!!, 0.001)
        assertEquals(0, checkIn.modules.size)
        assertNull(checkIn.resolvedAt)
    }

    @Test
    fun decodesAHeldRowWithNullProposedFields() {
        val row = """
            {
              "id": "checkin-2",
              "user_id": "user-1",
              "program_id": null,
              "week_start": "2026-07-20",
              "week_end": "2026-07-26",
              "status": "held",
              "previous_expenditure_kcal": null,
              "observed_expenditure_kcal": null,
              "proposed_expenditure_kcal": null,
              "proposed_calories": null,
              "proposed_protein_g": null,
              "proposed_carbs_g": null,
              "proposed_fat_g": null,
              "modules": [{"key": "weigh_in", "action": "add a weigh-in for each seven-day period"}],
              "explanation": "More history is required before updating expenditure.",
              "created_at": "2026-07-26T06:30:05Z",
              "resolved_at": null
            }
        """.trimIndent()

        val checkIn = json.decodeFromString(PersistedCheckIn.serializer(), row)

        assertEquals("held", checkIn.status)
        assertNull(checkIn.proposedCalories)
        assertEquals(1, checkIn.modules.size)
        assertEquals("weigh_in", checkIn.modules[0].key)
    }

    @Test
    fun encodesANewCheckInPayloadOmittingIdAndCreatedAtAndResolvedAt() {
        val payload = NewCheckIn(
            userId = "user-1",
            weekStart = "2026-07-27",
            weekEnd = "2026-08-02",
            status = "pending",
            previousExpenditureKcal = 2400.0,
            observedExpenditureKcal = 2450.0,
            proposedExpenditureKcal = 2250.0,
            proposedCalories = 2250.0,
            proposedProteinG = 160.0,
            proposedCarbsG = 200.0,
            proposedFatG = 70.0,
            modules = buildJsonArray {
                add(buildJsonObject { put("key", "weigh_in"); put("action", "add a weigh-in") })
            },
            explanation = "The next target uses observed expenditure and the signed goal rate.",
        )

        val encoded = json.encodeToString(NewCheckIn.serializer(), payload)

        // week_start/week_end/status/explanation/modules are all required
        // (non-optional) fields, so they always encode regardless of
        // encodeDefaults -- safe to assert their presence.
        assertEquals(true, encoded.contains("\"week_start\":\"2026-07-27\""))
        assertEquals(true, encoded.contains("\"status\":\"pending\""))
        assertEquals(true, encoded.contains("\"weigh_in\""))
        assertEquals(false, encoded.contains("\"id\""))
        assertEquals(false, encoded.contains("\"created_at\""))
        assertEquals(false, encoded.contains("\"resolved_at\""))
    }

    @Test
    fun checkInModuleDtoRoundTrips() {
        val dto = CheckInModuleDto(key = "logging_break", action = "carry forward the last high-confidence estimate")

        val encoded = json.encodeToString(CheckInModuleDto.serializer(), dto)
        val decoded = json.decodeFromString(CheckInModuleDto.serializer(), encoded)

        assertEquals(dto, decoded)
    }
}
