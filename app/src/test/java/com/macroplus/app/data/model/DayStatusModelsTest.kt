package com.macroplus.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayStatusModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesADailyLogStatusRow() {
        val row = """
            {
              "user_id": "user-1",
              "log_date": "2026-08-03",
              "status": "complete",
              "note": null
            }
        """.trimIndent()

        val status = json.decodeFromString(DailyLogStatus.serializer(), row)

        assertEquals(DayStatus.COMPLETE, status.status)
        assertNull(status.note)
    }

    @Test
    fun dayStatusConstantsMatchTheDbCheckConstraintValues() {
        // supabase/migrations/001_macro_foundation.sql:
        // status text not null check (status in ('complete', 'partial', 'fasted', 'unlogged'))
        assertEquals("complete", DayStatus.COMPLETE)
        assertEquals("partial", DayStatus.PARTIAL)
        assertEquals("fasted", DayStatus.FASTED)
        assertEquals("unlogged", DayStatus.UNLOGGED)
    }
}
