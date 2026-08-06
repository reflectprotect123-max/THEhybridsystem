package com.macroplus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentReferencesTest {
    private fun ref(id: String, loggedAt: String) = RecentLogReference(
        foodId = id,
        customFoodId = null,
        recipeId = null,
        displayName = "Food $id",
        loggedAt = loggedAt,
    )

    @Test
    fun keepsOnlyTheMostRecentOccurrenceOfEachFood() {
        val entries = listOf(
            ref("a", "2026-08-03T10:00:00Z"),
            ref("b", "2026-08-03T09:00:00Z"),
            ref("a", "2026-08-02T10:00:00Z"), // older duplicate of "a", must be dropped
            ref("c", "2026-08-01T10:00:00Z"),
        )

        val result = dedupeRecentReferences(entries, limit = 10)

        assertEquals(listOf("a", "b", "c"), result.map { it.foodId })
    }

    @Test
    fun respectsTheLimitAfterDeduping() {
        val entries = (1..5).map { ref(it.toString(), "2026-08-0${it}T10:00:00Z") }

        val result = dedupeRecentReferences(entries, limit = 2)

        assertEquals(2, result.size)
        assertEquals(listOf("1", "2"), result.map { it.foodId })
    }
}
