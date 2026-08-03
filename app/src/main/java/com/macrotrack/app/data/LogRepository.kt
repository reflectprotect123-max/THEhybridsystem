package com.macrotrack.app.data

import com.macrotrack.app.data.model.CustomFood
import com.macrotrack.app.data.model.DailyTotals
import com.macrotrack.app.data.model.EntryKind
import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.FoodLogEntry
import com.macrotrack.app.data.model.NewFoodLogEntry
import com.macrotrack.app.domain.MacroResolution
import com.macrotrack.app.domain.ScaledMacros
import com.macrotrack.app.domain.ServingScaler
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant
import java.time.LocalDate

interface LogRepository {
    suspend fun listEntries(date: LocalDate): List<FoodLogEntry>
    suspend fun getDailyTotals(date: LocalDate): DailyTotals?
    suspend fun logFood(date: LocalDate, food: Food, quantity: Double, unit: String, meal: String, notes: String? = null): FoodLogEntry
    suspend fun logCustomFood(date: LocalDate, customFood: CustomFood, quantity: Double, unit: String, meal: String, notes: String? = null): FoodLogEntry
    suspend fun logRecipeServings(date: LocalDate, recipeId: String, loggedServings: Double, meal: String, notes: String? = null): FoodLogEntry
    suspend fun logQuickAdd(date: LocalDate, displayName: String, calories: Double, proteinG: Double, carbsG: Double, fatG: Double, meal: String, notes: String? = null): FoodLogEntry
    suspend fun deleteEntry(entryId: String)
}

class SupabaseLogRepository(
    private val client: SupabaseClient,
    private val foodRepository: FoodRepository,
    private val recipeRepository: RecipeRepository,
    private val recipeMacroResolver: RecipeMacroResolver,
) : LogRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("LogRepository used before a user session exists.")
    }

    override suspend fun listEntries(date: LocalDate): List<FoodLogEntry> {
        val userId = requireUserId()
        return client.postgrest.from("food_log_entries").select {
            filter {
                eq("user_id", userId)
                eq("log_date", date.toString())
                exact("deleted_at", null)
            }
            order("created_at", Order.ASCENDING)
        }.decodeList<FoodLogEntry>()
    }

    /**
     * Returns the daily nutrition totals for a given date, or null if the day is unlogged.
     * Note: the underlying view may emit a row with entryCount=0 if all entries for a date were deleted;
     * we treat this identically to "no row" so a fully-deleted day never reads as a confident zero.
     */
    override suspend fun getDailyTotals(date: LocalDate): DailyTotals? {
        val userId = requireUserId()
        val totals = client.postgrest.from("daily_nutrition_totals").select {
            filter {
                eq("user_id", userId)
                eq("log_date", date.toString())
            }
            limit(1)
        }.decodeSingleOrNull<DailyTotals>()
        return totals?.takeIf { it.entryCount > 0 }
    }

    override suspend fun logFood(date: LocalDate, food: Food, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry {
        require(quantity > 0) { "quantity must be > 0, got $quantity" }
        val servings = foodRepository.getServings(food.id)
        val macros = MacroResolution.resolveFoodMacros(food, servings, quantity, unit)
        return insertEntry(
            date = date, entryKind = EntryKind.FOOD, foodId = food.id, customFoodId = null, recipeId = null,
            quantity = quantity, unit = unit, macros = macros, displayName = food.name, meal = meal, notes = notes,
        )
    }

    override suspend fun logCustomFood(date: LocalDate, customFood: CustomFood, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry {
        require(quantity > 0) { "quantity must be > 0, got $quantity" }
        val macros = ServingScaler.scale(customFood, quantity, unit)
        return insertEntry(
            date = date, entryKind = EntryKind.CUSTOM_FOOD, foodId = null, customFoodId = customFood.id, recipeId = null,
            quantity = quantity, unit = unit, macros = macros, displayName = customFood.name, meal = meal, notes = notes,
        )
    }

    override suspend fun logRecipeServings(date: LocalDate, recipeId: String, loggedServings: Double, meal: String, notes: String?): FoodLogEntry {
        require(loggedServings > 0) { "loggedServings must be > 0, got $loggedServings" }
        val recipe = recipeRepository.getById(recipeId) ?: error("Recipe $recipeId not found")
        val perServing = recipeMacroResolver.resolvePerServingMacros(recipe)
        val macros = MacroResolution.forLoggedServings(perServing, loggedServings)
        return insertEntry(
            date = date, entryKind = EntryKind.RECIPE, foodId = null, customFoodId = null, recipeId = recipe.id,
            quantity = loggedServings, unit = "serving", macros = macros, displayName = recipe.name, meal = meal, notes = notes,
        )
    }

    override suspend fun logQuickAdd(date: LocalDate, displayName: String, calories: Double, proteinG: Double, carbsG: Double, fatG: Double, meal: String, notes: String?): FoodLogEntry {
        return insertEntry(
            date = date, entryKind = EntryKind.QUICK_ADD, foodId = null, customFoodId = null, recipeId = null,
            quantity = 1.0, unit = "serving",
            macros = ScaledMacros(calories = calories, proteinG = proteinG, carbsG = carbsG, fatG = fatG),
            displayName = displayName, meal = meal, notes = notes,
        )
    }

    override suspend fun deleteEntry(entryId: String) {
        val userId = requireUserId()
        client.postgrest.from("food_log_entries").update({
            set("deleted_at", Instant.now().toString())
        }) {
            filter {
                eq("id", entryId)
                eq("user_id", userId)
            }
        }
    }

    private suspend fun insertEntry(
        date: LocalDate,
        entryKind: String,
        foodId: String?,
        customFoodId: String?,
        recipeId: String?,
        quantity: Double,
        unit: String,
        macros: ScaledMacros,
        displayName: String,
        meal: String,
        notes: String?,
    ): FoodLogEntry {
        val userId = requireUserId()
        val payload = NewFoodLogEntry(
            userId = userId,
            logDate = date.toString(),
            meal = meal,
            entryKind = entryKind,
            foodId = foodId,
            customFoodId = customFoodId,
            recipeId = recipeId,
            quantity = quantity,
            unit = unit,
            calories = macros.calories,
            proteinG = macros.proteinG,
            carbsG = macros.carbsG,
            fatG = macros.fatG,
            displayName = displayName,
            notes = notes,
        )
        return client.postgrest.from("food_log_entries").insert(payload) { select() }.decodeSingle<FoodLogEntry>()
    }
}
