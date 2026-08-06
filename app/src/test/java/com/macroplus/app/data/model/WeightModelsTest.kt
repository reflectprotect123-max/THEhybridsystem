package com.macroplus.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAWeightEntryRow() {
        val row = """
            {
              "id": "weight-1",
              "user_id": "user-1",
              "measured_at": "2026-08-03T06:30:00Z",
              "weight_kg": 82.4,
              "source": "manual",
              "note": "morning, fasted",
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val entry = json.decodeFromString(WeightEntry.serializer(), row)

        assertEquals("weight-1", entry.id)
        assertEquals("user-1", entry.userId)
        assertEquals("2026-08-03T06:30:00Z", entry.measuredAt)
        assertEquals(82.4, entry.weightKg, 0.001)
        assertEquals("manual", entry.source)
        assertEquals("morning, fasted", entry.note)
        assertEquals("2026-08-03T06:30:05Z", entry.createdAt)
    }

    @Test
    fun decodesAWeightEntryRowWithNoNote() {
        val row = """
            {
              "id": "weight-2",
              "user_id": "user-1",
              "measured_at": "2026-08-04T06:30:00Z",
              "weight_kg": 82.1,
              "source": "manual",
              "note": null,
              "created_at": "2026-08-04T06:30:05Z"
            }
        """.trimIndent()

        val entry = json.decodeFromString(WeightEntry.serializer(), row)

        assertNull(entry.note)
    }

    @Test
    fun encodesANewWeightEntryPayloadOmittingServerGeneratedColumns() {
        val payload = NewWeightEntry(
            userId = "user-1",
            measuredAt = "2026-08-03T06:30:00Z",
            weightKg = 82.4,
        )

        // kotlinx.serialization's default Json leaves encodeDefaults = false, so a
        // default-valued property (source = "manual" here) is omitted from the
        // output rather than written out -- asserting it's present would describe
        // a wire payload this Json instance never actually produces.
        val encoded = json.encodeToString(NewWeightEntry.serializer(), payload)

        assertEquals(true, encoded.contains("\"weight_kg\":82.4"))
        assertEquals(false, encoded.contains("\"id\""))
        assertEquals(false, encoded.contains("\"created_at\""))
    }

    @Test
    fun encodesANewWeightEntryPayloadWithAnExplicitNonDefaultSource() {
        val payload = NewWeightEntry(
            userId = "user-1",
            measuredAt = "2026-08-03T06:30:00Z",
            weightKg = 82.4,
            source = "healthconnect",
        )

        val encoded = json.encodeToString(NewWeightEntry.serializer(), payload)

        assertEquals(true, encoded.contains("\"source\":\"healthconnect\""))
    }
}
