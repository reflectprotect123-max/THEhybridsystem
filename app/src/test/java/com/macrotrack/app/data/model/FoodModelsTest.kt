package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAFoodRowFromPostgrestJson() {
        val row = """
            {
              "id": "b6a1f2c0-1111-4a2b-9c3d-000000000001",
              "name": "Rolled Oats",
              "brand": null,
              "barcode": null,
              "serving_qty": 100,
              "serving_unit": "g",
              "calories": 379,
              "protein_g": 13.2,
              "carbs_g": 67.7,
              "fat_g": 6.9,
              "source": "ausnut",
              "external_id": "12-345",
              "nutrition_basis_qty": 100,
              "nutrition_basis_unit": "g",
              "serving_size_text": "100 g"
            }
        """.trimIndent()

        val food = json.decodeFromString(Food.serializer(), row)

        assertEquals("Rolled Oats", food.name)
        assertNull(food.brand)
        assertEquals(100.0, food.servingQty, 0.001)
        assertEquals("g", food.servingUnit)
        assertEquals(379.0, food.calories, 0.001)
        assertEquals("ausnut", food.source)
        assertEquals("12-345", food.externalId)
    }

    @Test
    fun decodesAFoodServingRow() {
        val row = """
            {
              "id": "c7b2f3d0-2222-4a2b-9c3d-000000000002",
              "food_id": "b6a1f2c0-1111-4a2b-9c3d-000000000001",
              "label": "1 cup",
              "quantity": 1,
              "unit": "cup",
              "grams": 90,
              "millilitres": null,
              "is_default": true,
              "sort_order": 0
            }
        """.trimIndent()

        val serving = json.decodeFromString(FoodServing.serializer(), row)

        assertEquals("1 cup", serving.label)
        assertEquals(90.0, serving.grams!!, 0.001)
        assertNull(serving.millilitres)
        assertEquals(true, serving.isDefault)
    }
}
