package com.macroplus.app.domain

/** Builds safe `ilike` search patterns for Postgrest text search. */
object SearchPatterns {
    fun ilikePattern(rawQuery: String): String {
        val escaped = rawQuery.trim()
            .lowercase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
