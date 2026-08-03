package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogEntryModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAFoodLogEntryRow() {
        val row = """
            {
              "id": "entry-1",
              "user_id": "user-1",
              "log_date": "2026-08-03",
              "meal": "lunch",
              "entry_kind": "food",
              "food_id": "food-1",
              "custom_food_id": null,
              "recipe_id": null,
              "quantity": 150,
              "unit": "g",
              "calories": 248,
              "protein_g": 46.5,
              "carbs_g": 0,
              "fat_g": 5.4,
              "display_name": "Grilled Chicken Breast",
              "notes": null
            }
        """.trimIndent()

        val entry = json.decodeFromString(FoodLogEntry.serializer(), row)

        assertEquals("lunch", entry.meal)
        assertEquals(EntryKind.FOOD, entry.entryKind)
        assertEquals("food-1", entry.foodId)
        assertNull(entry.customFoodId)
        assertEquals(248.0, entry.calories, 0.001)
        assertEquals("Grilled Chicken Breast", entry.displayName)
    }

    @Test
    fun decodesDailyTotalsFromTheView() {
        val row = """
            {
              "user_id": "user-1",
              "log_date": "2026-08-03",
              "calories": 1840,
              "protein_g": 132,
              "carbs_g": 190,
              "fat_g": 58,
              "entry_count": 7
            }
        """.trimIndent()

        val totals = json.decodeFromString(DailyTotals.serializer(), row)

        assertEquals(1840.0, totals.calories, 0.001)
        assertEquals(7, totals.entryCount)
    }
}
