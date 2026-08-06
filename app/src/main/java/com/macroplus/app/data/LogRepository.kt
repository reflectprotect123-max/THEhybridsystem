package com.macroplus.app.data

import com.macroplus.app.data.model.CustomFood
import com.macroplus.app.data.model.DailyTotals
import com.macroplus.app.data.model.EntryKind
import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodLogEntry
import com.macroplus.app.data.model.NewFoodLogEntry
import com.macroplus.app.domain.MacroResolution
import com.macroplus.app.domain.ScaledMacros
import com.macroplus.app.domain.ServingScaler
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

interface LogRepository {
    suspend fun listEntries(date: LocalDate): List<FoodLogEntry>
    suspend fun getDailyTotals(date: LocalDate): DailyTotals?
    suspend fun listDailyTotals(since: LocalDate): List<DailyTotals>
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

    override suspend fun listDailyTotals(since: LocalDate): List<DailyTotals> {
        val userId = requireUserId()
        return client.postgrest.from("daily_nutrition_totals").select {
            filter {
                eq("user_id", userId)
                gte("log_date", since.toString())
            }
            order("log_date", Order.ASCENDING)
        }.decodeList<DailyTotals>().filter { it.entryCount > 0 }
    }

    override suspend fun logFood(date: LocalDate, food: Food, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry {
        require(quantity.isFinite() && quantity > 0) { "quantity must be finite and > 0, got $quantity" }
        val servings = foodRepository.getServings(food.id)
        val macros = MacroResolution.resolveFoodMacros(food, servings, quantity, unit)
        return insertEntry(
            date = date, entryKind = EntryKind.FOOD, foodId = food.id, customFoodId = null, recipeId = null,
            quantity = quantity,
            unit = unit,
            macros = macros,
            displayName = food.name,
            meal = meal,
            notes = notes,
            nutrients = food.nutrients,
            sourceSnapshot = foodSnapshot(food, quantity, unit, macros),
        )
    }

    override suspend fun logCustomFood(date: LocalDate, customFood: CustomFood, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry {
        require(quantity.isFinite() && quantity > 0) { "quantity must be finite and > 0, got $quantity" }
        val macros = ServingScaler.scale(customFood, quantity, unit)
        return insertEntry(
            date = date, entryKind = EntryKind.CUSTOM_FOOD, foodId = null, customFoodId = customFood.id, recipeId = null,
            quantity = quantity,
            unit = unit,
            macros = macros,
            displayName = customFood.name,
            meal = meal,
            notes = notes,
            nutrients = customFood.nutrients,
            sourceSnapshot = customFoodSnapshot(customFood, quantity, unit, macros),
        )
    }

    override suspend fun logRecipeServings(date: LocalDate, recipeId: String, loggedServings: Double, meal: String, notes: String?): FoodLogEntry {
        require(loggedServings.isFinite() && loggedServings > 0) {
            "loggedServings must be finite and > 0, got $loggedServings"
        }
        val recipe = recipeRepository.getById(recipeId) ?: error("Recipe $recipeId not found")
        val perServing = recipeMacroResolver.resolvePerServingMacros(recipe)
        val macros = MacroResolution.forLoggedServings(perServing, loggedServings)
        return insertEntry(
            date = date, entryKind = EntryKind.RECIPE, foodId = null, customFoodId = null, recipeId = recipe.id,
            quantity = loggedServings,
            unit = "serving",
            macros = macros,
            displayName = recipe.name,
            meal = meal,
            notes = notes,
            sourceSnapshot = recipeSnapshot(recipe, loggedServings, macros),
        )
    }

    override suspend fun logQuickAdd(date: LocalDate, displayName: String, calories: Double, proteinG: Double, carbsG: Double, fatG: Double, meal: String, notes: String?): FoodLogEntry {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(meal.isNotBlank()) { "meal must not be blank" }
        require(listOf(calories, proteinG, carbsG, fatG).all { it.isFinite() && it >= 0 }) {
            "Quick-add nutrition values must be finite and non-negative"
        }
        return insertEntry(
            date = date, entryKind = EntryKind.QUICK_ADD, foodId = null, customFoodId = null, recipeId = null,
            quantity = 1.0, unit = "serving",
            macros = ScaledMacros(calories = calories, proteinG = proteinG, carbsG = carbsG, fatG = fatG),
            displayName = displayName,
            meal = meal,
            notes = notes,
            sourceSnapshot = buildJsonObject {
                put("kind", EntryKind.QUICK_ADD)
                put("display_name", displayName)
                put("calories", calories)
                put("protein_g", proteinG)
                put("carbs_g", carbsG)
                put("fat_g", fatG)
            },
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
        nutrients: JsonObject = buildJsonObject { },
        sourceSnapshot: JsonObject = buildJsonObject { },
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
            nutrients = nutrients,
            sourceSnapshot = sourceSnapshot,
        )
        return client.postgrest.from("food_log_entries").insert(payload) { select() }.decodeSingle<FoodLogEntry>()
    }

    private fun foodSnapshot(food: Food, quantity: Double, unit: String, macros: ScaledMacros) = buildJsonObject {
        put("kind", EntryKind.FOOD)
        put("food_id", food.id)
        put("name", food.name)
        food.brand?.let { put("brand", it) }
        food.barcode?.let { put("barcode", it) }
        put("source", food.source)
        food.externalId?.let { put("external_id", it) }
        put("serving_qty", food.servingQty)
        put("serving_unit", food.servingUnit)
        put("nutrition_basis_qty", food.nutritionBasisQty)
        put("nutrition_basis_unit", food.nutritionBasisUnit)
        food.servingSizeText?.let { put("serving_size_text", it) }
        put("logged_quantity", quantity)
        put("logged_unit", unit)
        put("logged_calories", macros.calories)
        put("logged_protein_g", macros.proteinG)
        put("logged_carbs_g", macros.carbsG)
        put("logged_fat_g", macros.fatG)
    }

    private fun customFoodSnapshot(customFood: CustomFood, quantity: Double, unit: String, macros: ScaledMacros) = buildJsonObject {
        put("kind", EntryKind.CUSTOM_FOOD)
        put("custom_food_id", customFood.id)
        put("name", customFood.name)
        customFood.brand?.let { put("brand", it) }
        customFood.barcode?.let { put("barcode", it) }
        put("source", "user_custom")
        put("serving_qty", customFood.servingQty)
        put("serving_unit", customFood.servingUnit)
        put("logged_quantity", quantity)
        put("logged_unit", unit)
        put("logged_calories", macros.calories)
        put("logged_protein_g", macros.proteinG)
        put("logged_carbs_g", macros.carbsG)
        put("logged_fat_g", macros.fatG)
    }

    private fun recipeSnapshot(recipe: com.macroplus.app.data.model.Recipe, loggedServings: Double, macros: ScaledMacros) = buildJsonObject {
        put("kind", EntryKind.RECIPE)
        put("recipe_id", recipe.id)
        put("name", recipe.name)
        put("recipe_servings", recipe.servings)
        put("logged_servings", loggedServings)
        put("per_logged_calories", macros.calories)
        put("per_logged_protein_g", macros.proteinG)
        put("per_logged_carbs_g", macros.carbsG)
        put("per_logged_fat_g", macros.fatG)
    }
}
