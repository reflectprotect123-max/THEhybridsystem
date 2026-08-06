package com.macroplus.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroProgramModelsTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun newProgramEncodesTheFieldsNeededToPersistTheUserGoal() {
        val program = NewMacroProgram(
            userId = "user-1",
            name = "My macro goal",
            mode = "manual",
            goal = "lose",
            targetRateKgPerWeek = -0.3,
            startDate = "2026-08-04",
        )

        val encoded = json.encodeToString(NewMacroProgram.serializer(), program)

        assertTrue(encoded.contains("\"target_rate_kg_per_week\":-0.3"))
        assertTrue(encoded.contains("\"goal\":\"lose\""))
        // `status` defaults to "active" on NewMacroProgram, and this app
        // encodes with `encodeDefaults = false`, so a field left at its
        // default is OMITTED from the body entirely rather than encoded.
        // That is fine for this insert-only payload -- the schema column
        // defaults to 'active' as well -- but the test must assert the real
        // behaviour, not a value that can never appear.
        assertEquals(false, encoded.contains("\"status\""))
    }

    @Test
    fun newProgramEncodesAnExplicitlyNonDefaultStatus() {
        val program = NewMacroProgram(
            userId = "user-1",
            name = "Paused goal",
            mode = "manual",
            goal = "maintain",
            targetRateKgPerWeek = 0.0,
            startDate = "2026-08-04",
            status = "paused",
        )

        val encoded = json.encodeToString(NewMacroProgram.serializer(), program)

        // A caller that deliberately sets a non-default status must have it
        // transmitted; `encodeDefaults = false` only drops values equal to
        // the declared default.
        assertTrue(encoded.contains("\"status\":\"paused\""))
    }

    @Test
    fun programDecodesNullableOptionalFields() {
        val row = """
            {
              "id":"program-1",
              "user_id":"user-1",
              "name":"Maintenance",
              "mode":"manual",
              "goal":"maintain",
              "target_rate_kg_per_week":0.0,
              "start_date":"2026-08-04",
              "end_date":null,
              "weekly_calorie_budget":null,
              "protein_preference":null,
              "fat_preference":null,
              "status":"active",
              "created_at":"2026-08-04T00:00:00Z",
              "updated_at":"2026-08-04T00:00:00Z"
            }
        """.trimIndent()

        val decoded = Json.decodeFromString(MacroProgram.serializer(), row)

        assertEquals("maintain", decoded.goal)
        assertEquals(0.0, decoded.targetRateKgPerWeek, 0.001)
        assertEquals(null, decoded.endDate)
    }

    @Test
    fun acceptedDayTargetCarriesItsProvenance() {
        val target = NewMacroProgramDay(
            programId = "program-1",
            targetDate = "2026-08-10",
            calories = 2400.0,
            proteinG = 160.0,
            carbsG = 280.0,
            fatG = 70.0,
            source = "accepted_check_in",
        )

        val encoded = json.encodeToString(NewMacroProgramDay.serializer(), target)

        assertTrue(encoded.contains("\"source\":\"accepted_check_in\""))
        assertTrue(encoded.contains("\"target_date\":\"2026-08-10\""))
    }

    /**
     * `NewMacroProgramDay` is written through `upsert(onConflict =
     * "program_id,target_date")`, and PostgREST's upsert only updates columns
     * that are actually present in the JSON body. With `encodeDefaults =
     * false`, any field that were to gain a Kotlin default would be silently
     * dropped whenever its value equalled that default -- leaving the prior
     * row's value in place instead of overwriting it. Every field on this
     * payload is therefore a required constructor parameter. This test pins
     * that: it encodes a payload whose values are exactly the "obvious
     * default" ones (0.0 / empty-ish) and asserts all seven keys still
     * appear, so adding a default to any of them fails here rather than
     * silently corrupting an update.
     */
    @Test
    fun everyUpsertDayTargetFieldIsTransmittedEvenAtDefaultLookingValues() {
        val target = NewMacroProgramDay(
            programId = "program-1",
            targetDate = "2026-08-10",
            calories = 0.0,
            proteinG = 0.0,
            carbsG = 0.0,
            fatG = 0.0,
            source = "",
        )

        val encoded = json.encodeToString(NewMacroProgramDay.serializer(), target)

        assertTrue(encoded.contains("\"program_id\":\"program-1\""))
        assertTrue(encoded.contains("\"target_date\":\"2026-08-10\""))
        assertTrue(encoded.contains("\"calories\":0.0"))
        assertTrue(encoded.contains("\"protein_g\":0.0"))
        assertTrue(encoded.contains("\"carbs_g\":0.0"))
        assertTrue(encoded.contains("\"fat_g\":0.0"))
        assertTrue(encoded.contains("\"source\":\"\""))
    }
}
