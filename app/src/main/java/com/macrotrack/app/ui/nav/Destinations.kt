package com.macrotrack.app.ui.nav

object Destinations {
    const val AUTH = "auth"
    const val DAILY_LOG = "daily_log"
    const val FOOD_SEARCH = "food_search"

    private const val ADD_LOG_ENTRY_BASE = "add_log_entry"
    const val ADD_LOG_ENTRY_PATTERN = "$ADD_LOG_ENTRY_BASE/{entryKind}/{id}"

    /** `entryKind` is one of `EntryKind.FOOD`/`CUSTOM_FOOD`/`RECIPE` (com.macrotrack.app.data.model.EntryKind). */
    fun addLogEntryRoute(entryKind: String, id: String): String = "$ADD_LOG_ENTRY_BASE/$entryKind/$id"
}
