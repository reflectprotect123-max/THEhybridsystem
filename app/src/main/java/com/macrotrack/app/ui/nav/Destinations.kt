package com.macrotrack.app.ui.nav

import java.time.LocalDate

object Destinations {
    const val AUTH = "auth"
    const val DAILY_LOG = "daily_log"
    const val WEIGHT = "weight"
    const val COACH = "coach"
    private const val FOOD_SEARCH_BASE = "food_search"
    const val BARCODE_SCANNER = "barcode_scanner"
    private const val CREATE_CUSTOM_FOOD_BASE = "create_custom_food"
    const val SCANNED_BARCODE_KEY = "scanned_barcode"
    const val NUTRITION_LABEL_SCANNER = "nutrition_label_scanner"
    const val SCANNED_LABEL_CALORIES_KEY = "scanned_label_calories"
    const val SCANNED_LABEL_PROTEIN_KEY = "scanned_label_protein"
    const val SCANNED_LABEL_CARBS_KEY = "scanned_label_carbs"
    const val SCANNED_LABEL_FAT_KEY = "scanned_label_fat"
    const val SCANNED_LABEL_SERVING_QTY_KEY = "scanned_label_serving_qty"
    const val SCANNED_LABEL_SERVING_UNIT_KEY = "scanned_label_serving_unit"

    private const val ADD_LOG_ENTRY_BASE = "add_log_entry"
    const val FOOD_SEARCH_PATTERN = "$FOOD_SEARCH_BASE/{logDate}"
    const val CREATE_CUSTOM_FOOD_PATTERN = "$CREATE_CUSTOM_FOOD_BASE/{logDate}"
    private const val QUICK_ADD_BASE = "quick_add"
    const val QUICK_ADD_PATTERN = "$QUICK_ADD_BASE/{logDate}"
    private const val RECIPE_BUILDER_BASE = "create_recipe"
    const val RECIPE_BUILDER_PATTERN = "$RECIPE_BUILDER_BASE/{logDate}"
    const val ADD_LOG_ENTRY_PATTERN = "$ADD_LOG_ENTRY_BASE/{entryKind}/{id}/{logDate}"

    /** `entryKind` is one of `EntryKind.FOOD`/`CUSTOM_FOOD`/`RECIPE` (com.macrotrack.app.data.model.EntryKind). */
    fun foodSearchRoute(logDate: LocalDate): String = "$FOOD_SEARCH_BASE/$logDate"
    fun createCustomFoodRoute(logDate: LocalDate): String = "$CREATE_CUSTOM_FOOD_BASE/$logDate"
    fun quickAddRoute(logDate: LocalDate): String = "$QUICK_ADD_BASE/$logDate"
    fun recipeBuilderRoute(logDate: LocalDate): String = "$RECIPE_BUILDER_BASE/$logDate"
    fun addLogEntryRoute(entryKind: String, id: String, logDate: LocalDate): String =
        "$ADD_LOG_ENTRY_BASE/$entryKind/$id/$logDate"
}
