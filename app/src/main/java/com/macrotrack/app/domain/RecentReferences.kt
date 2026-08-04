package com.macrotrack.app.domain

data class RecentLogReference(
    val foodId: String?,
    val customFoodId: String?,
    val recipeId: String?,
    val displayName: String,
    val loggedAt: String,
)

/**
 * Keeps only the first (most recent, given [entries] is already sorted
 * newest-first) occurrence of each food/custom-food/recipe identity, capped
 * at [limit]. PostgREST has no DISTINCT ON, so this is done client-side —
 * see Task 7's notes in the plan for why.
 */
fun dedupeRecentReferences(entries: List<RecentLogReference>, limit: Int): List<RecentLogReference> {
    val seen = mutableSetOf<Triple<String?, String?, String?>>()
    val result = mutableListOf<RecentLogReference>()
    for (entry in entries) {
        if (result.size >= limit) break
        val key = Triple(entry.foodId, entry.customFoodId, entry.recipeId)
        if (seen.add(key)) {
            result.add(entry)
        }
    }
    return result
}
