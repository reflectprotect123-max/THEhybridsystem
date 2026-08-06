# Food Repository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android data-layer repositories for foods, custom foods, recipes, favorites, and recent-food lookups on top of the existing Supabase schema, so later slices (daily logger, barcode UI) have a stable API to call.

**Architecture:** Thin Kotlin repository classes wrap `supabase-kt`'s Postgrest client with typed queries. All macro-scaling and search-pattern logic is extracted into pure, dependency-free functions so it can be unit-tested without a live Supabase project or Android SDK. Repositories return domain models (`kotlinx.serialization` data classes matching the DB columns), never raw JSON.

**Tech Stack:** Kotlin, `io.github.jan-tennert.supabase:postgrest-kt` 3.7.0, `io.github.jan-tennert.supabase:auth-kt` 3.7.0, `kotlinx.serialization.json`, JUnit 4 (already in `app/build.gradle.kts`).

## Global Constraints

These are copied verbatim from `CLAUDE.md`'s non-negotiable data rules and apply to every task below:

- Never invent nutrition numbers, barcode values, serving weights, food densities, or nutrient units. If a unit conversion can't be done exactly, fail loudly (throw) instead of guessing.
- Preserve source provenance — repositories return the `source`/`external_id` fields as stored, never strip or overwrite them.
- Keep barcode data separate from generic foods; AUSNUT/NUTTAB rows have `barcode = NULL`. `findByBarcode` must treat "no barcode" as "not found," never as a match.
- Keep the service-role Supabase key out of Android — every repository in this plan uses the existing `SupabaseClientProvider` (publishable/anon key + RLS), never a service-role client.
- Do not make destructive schema changes. This plan adds no migrations; it only reads/writes rows within the schema already in `supabase/migrations/001_macro_foundation.sql`.

**Sandbox note:** this plan was authored in an environment with no Android SDK and a network policy that blocks `dl.google.com` (Google's Maven repo), so `./gradlew :app:testDebugUnitTest` cannot run here — confirmed in the prior audit session. Every "run test" step below must be executed on a real Android Studio machine. The Postgrest/Auth API calls in this plan (`postgrest.from`, `.select { filter { } }`, `.insert`, `.decodeList`, `auth.currentUserOrNull()`) were verified against the actual `postgrest-kt` and `auth-kt` 3.7.0 sources JARs pulled from Maven Central (not guessed), so the code should compile as written, but has not been run against a live Supabase project.

---

## File Structure

```
app/src/main/java/com/macroplus/app/
  data/
    model/
      FoodModels.kt          # Food, FoodServing (decode-only, matches `foods`/`food_servings`)
      CustomFoodModels.kt     # CustomFood (decode) + NewCustomFood (insert payload)
      RecipeModels.kt         # Recipe, RecipeItem (decode) + NewRecipe, NewRecipeItem (insert payloads)
      FavoriteModels.kt       # FoodFavorite (decode) + NewFoodFavorite (insert payload)
      RecentLogModels.kt      # RecentLogEntryRow (decode), RecentLogReference (domain)
    FoodRepository.kt         # barcode lookup, name/brand search, servings
    CustomFoodRepository.kt   # CRUD for the current user's custom foods
    RecipeRepository.kt       # CRUD for recipes + recipe items
    FavoritesRepository.kt    # add/remove/list favorites
    RecentFoodRepository.kt   # recent foods from food_log_entries
    AppContainer.kt           # wires SupabaseClientProvider.create() to the repositories above
  domain/
    ServingScaler.kt          # pure macro-scaling math (no I/O)
    SearchPatterns.kt         # pure ilike-pattern building/escaping (no I/O)
app/src/test/java/com/macroplus/app/
  domain/
    ServingScalerTest.kt
    SearchPatternsTest.kt
  data/model/
    FoodModelsTest.kt
```

`AdaptiveNutrition.kt` and its test are untouched by this plan — this is the food repository slice only, not the adaptive engine.

---

### Task 1: Food and FoodServing models

**Files:**
- Create: `app/src/main/java/com/macroplus/app/data/model/FoodModels.kt`
- Test: `app/src/test/java/com/macroplus/app/data/model/FoodModelsTest.kt`

**Interfaces:**
- Consumes: nothing (leaf task)
- Produces: `Food` data class (fields: `id, name, brand, barcode, servingQty, servingUnit, calories, proteinG, carbsG, fatG, source, externalId, nutritionBasisQty, nutritionBasisUnit, servingSizeText`), `FoodServing` data class (fields: `id, foodId, label, quantity, unit, grams, millilitres, isDefault, sortOrder`). Later tasks decode Postgrest rows into these types.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macroplus.app.data.model

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.data.model.FoodModelsTest"`
Expected: FAIL — compile error, `Food`/`FoodServing` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors `public.foods` in supabase/migrations/001_macro_foundation.sql.
 * calories/protein_g/carbs_g/fat_g are the amounts for `servingQty` of
 * `servingUnit` on THIS row — not per `nutritionBasisQty`/`nutritionBasisUnit`,
 * which only records what the original source's denominator was (see
 * import_openfoodfacts.py's make_row: it scales macros to serving_qty before
 * writing the row). Scale off servingQty/servingUnit, not the basis fields.
 */
@Serializable
data class Food(
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") val servingQty: Double,
    @SerialName("serving_unit") val servingUnit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    val source: String,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("nutrition_basis_qty") val nutritionBasisQty: Double,
    @SerialName("nutrition_basis_unit") val nutritionBasisUnit: String,
    @SerialName("serving_size_text") val servingSizeText: String? = null,
)

/** Mirrors `public.food_servings`. */
@Serializable
data class FoodServing(
    val id: String,
    @SerialName("food_id") val foodId: String,
    val label: String,
    val quantity: Double,
    val unit: String,
    val grams: Double? = null,
    val millilitres: Double? = null,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("sort_order") val sortOrder: Int = 0,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.data.model.FoodModelsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macroplus/app/data/model/FoodModels.kt app/src/test/java/com/macroplus/app/data/model/FoodModelsTest.kt
git commit -m "feat: add Food and FoodServing models"
```

---

### Task 2: ServingScaler (pure macro-scaling logic)

**Files:**
- Create: `app/src/main/java/com/macroplus/app/domain/ServingScaler.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/ServingScalerTest.kt`

**Interfaces:**
- Consumes: `Food`, `FoodServing` from Task 1
- Produces: `ScaledMacros(calories: Double, proteinG: Double, carbsG: Double, fatG: Double)`, `ServingScaler.scale(food, quantity, unit): ScaledMacros`, `ServingScaler.scaleByServing(food, serving, servingCount): ScaledMacros`, `ServingScaler.IncompatibleUnitException`. Later tasks (the daily logger, in a future slice) will call these to compute the calories/macros to store on a log entry.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macroplus.app.domain

import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodServing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServingScalerTest {
    private val oats = Food(
        id = "food-1",
        name = "Rolled Oats",
        brand = null,
        barcode = null,
        servingQty = 100.0,
        servingUnit = "g",
        calories = 379.0,
        proteinG = 13.2,
        carbsG = 67.7,
        fatG = 6.9,
        source = "ausnut",
        externalId = "12-345",
        nutritionBasisQty = 100.0,
        nutritionBasisUnit = "g",
        servingSizeText = "100 g",
    )

    @Test
    fun scalesMacrosLinearlyWithinTheSameUnit() {
        val result = ServingScaler.scale(oats, quantity = 250.0, unit = "g")

        assertEquals(947.5, result.calories, 0.001)
        assertEquals(33.0, result.proteinG, 0.001)
        assertEquals(169.25, result.carbsG, 0.001)
        assertEquals(17.25, result.fatG, 0.001)
    }

    @Test
    fun throwsRatherThanGuessingAUnitConversion() {
        assertThrows(ServingScaler.IncompatibleUnitException::class.java) {
            ServingScaler.scale(oats, quantity = 1.0, unit = "cup")
        }
    }

    @Test
    fun scalesUsingAnExplicitFoodServingConversion() {
        val cupServing = FoodServing(
            id = "serving-1",
            foodId = "food-1",
            label = "1 cup",
            quantity = 1.0,
            unit = "cup",
            grams = 90.0,
            millilitres = null,
            isDefault = true,
            sortOrder = 0,
        )

        val result = ServingScaler.scaleByServing(oats, cupServing, servingCount = 2.0)

        // 2 cups = 180 g; 180 / 100 = 1.8x
        assertEquals(682.2, result.calories, 0.001)
        assertEquals(23.76, result.proteinG, 0.001)
    }

    @Test
    fun throwsWhenTheServingHasNoConversionForTheFoodsBasisUnit() {
        val servingWithoutGrams = FoodServing(
            id = "serving-2",
            foodId = "food-1",
            label = "1 packet",
            quantity = 1.0,
            unit = "packet",
            grams = null,
            millilitres = null,
            isDefault = false,
            sortOrder = 1,
        )

        assertThrows(ServingScaler.IncompatibleUnitException::class.java) {
            ServingScaler.scaleByServing(oats, servingWithoutGrams)
        }
    }

    @Test
    fun rejectsAServingThatBelongsToADifferentFood() {
        val mismatchedServing = FoodServing(
            id = "serving-3",
            foodId = "food-999",
            label = "1 cup",
            quantity = 1.0,
            unit = "cup",
            grams = 90.0,
            millilitres = null,
            isDefault = true,
            sortOrder = 0,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ServingScaler.scaleByServing(oats, mismatchedServing)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.ServingScalerTest"`
Expected: FAIL — `ServingScaler` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macroplus.app.domain

import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodServing

data class ScaledMacros(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

/**
 * Scales a Food's stored macros (which are per `food.servingQty` of
 * `food.servingUnit`) to a requested quantity. Never converts between mass
 * and volume (e.g. g <-> ml) by guessing a density — CLAUDE.md's
 * non-negotiable rule #1. A cross-unit conversion is only allowed via an
 * explicit FoodServing row that records the real grams/millilitres for that
 * serving, because that value came from the source data, not a guess.
 */
object ServingScaler {
    class IncompatibleUnitException(message: String) : Exception(message)

    fun scale(food: Food, quantity: Double, unit: String): ScaledMacros {
        if (!unit.equals(food.servingUnit, ignoreCase = true)) {
            throw IncompatibleUnitException(
                "Cannot scale ${food.name}: requested unit '$unit' does not match " +
                    "this food's serving unit '${food.servingUnit}'. Use a food_servings " +
                    "entry with an explicit gram/millilitre conversion instead of guessing a density."
            )
        }
        val multiplier = quantity / food.servingQty
        return scaleByMultiplier(food, multiplier)
    }

    fun scaleByServing(food: Food, serving: FoodServing, servingCount: Double = 1.0): ScaledMacros {
        require(serving.foodId == food.id) {
            "Serving ${serving.id} belongs to food ${serving.foodId}, not ${food.id}"
        }
        val basisUnit = food.servingUnit.lowercase()
        val perServingAmount = when (basisUnit) {
            "g" -> serving.grams
            "ml" -> serving.millilitres
            else -> null
        } ?: throw IncompatibleUnitException(
            "Serving '${serving.label}' for ${food.name} has no $basisUnit conversion recorded."
        )
        val multiplier = (perServingAmount * servingCount) / food.servingQty
        return scaleByMultiplier(food, multiplier)
    }

    private fun scaleByMultiplier(food: Food, multiplier: Double) = ScaledMacros(
        calories = food.calories * multiplier,
        proteinG = food.proteinG * multiplier,
        carbsG = food.carbsG * multiplier,
        fatG = food.fatG * multiplier,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.ServingScalerTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macroplus/app/domain/ServingScaler.kt app/src/test/java/com/macroplus/app/domain/ServingScalerTest.kt
git commit -m "feat: add ServingScaler for exact serving-size macro scaling"
```

---

### Task 3: SearchPatterns (pure ilike-pattern building)

**Files:**
- Create: `app/src/main/java/com/macroplus/app/domain/SearchPatterns.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/SearchPatternsTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `SearchPatterns.ilikePattern(rawQuery: String): String`. Task 4's `FoodRepository.search` calls this before building the Postgrest `ilike` filter.

**Why this exists:** Postgrest's `ilike` treats `%` and `_` in the search string as wildcards. If a user searches for a product literally named `"100% Whole Wheat"`, the `%` must be escaped or it silently becomes a wildcard and returns unrelated rows — this is a real correctness bug, not a style nit.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macroplus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPatternsTest {
    @Test
    fun wrapsTheQueryWithWildcardsAndLowercases() {
        assertEquals("%chicken breast%", SearchPatterns.ilikePattern("Chicken Breast"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("%oats%", SearchPatterns.ilikePattern("  oats  "))
    }

    @Test
    fun escapesLiteralPercentAndUnderscoreInUserInput() {
        assertEquals("%100\\% whole wheat%", SearchPatterns.ilikePattern("100% whole wheat"))
        assertEquals("%greek\\_yogurt%", SearchPatterns.ilikePattern("greek_yogurt"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.SearchPatternsTest"`
Expected: FAIL — `SearchPatterns` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.SearchPatternsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macroplus/app/domain/SearchPatterns.kt app/src/test/java/com/macroplus/app/domain/SearchPatternsTest.kt
git commit -m "feat: add SearchPatterns for safe ilike search queries"
```

---

### Task 4: FoodRepository

**Files:**
- Create: `app/src/main/java/com/macroplus/app/data/FoodRepository.kt`

**Interfaces:**
- Consumes: `Food`, `FoodServing` (Task 1), `SearchPatterns.ilikePattern` (Task 3), `SupabaseClient` (existing, from `SupabaseClientProvider`)
- Produces: `FoodRepository` interface with `suspend fun findByBarcode(barcode: String): Food?`, `suspend fun search(query: String, limit: Int = 20): List<Food>`, `suspend fun getServings(foodId: String): List<FoodServing>`; `SupabaseFoodRepository` implementation. Task 8 (`AppContainer`) constructs and exposes this.

**No unit test in this task.** This repository is a thin wrapper around network calls to a live Postgrest endpoint — there is no Supabase project connected in this environment to test against (see Global Constraints), and mocking the Postgrest DSL would only prove the mock behaves as configured, not that the real query is correct. Verify manually against a real Supabase project once `local.properties` has real credentials: insert a known row via `seed_foods_ausnut.sql`, then confirm `findByBarcode`/`search` return it. Add an integration test suite later if/when a test Supabase project is available — track this as a follow-up, don't fake a unit test now.

- [ ] **Step 1: Write the implementation**

```kotlin
package com.macroplus.app.data

import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodServing
import com.macroplus.app.domain.SearchPatterns
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface FoodRepository {
    /** Exact match only. A food with `barcode = NULL` never matches. */
    suspend fun findByBarcode(barcode: String): Food?
    suspend fun search(query: String, limit: Int = 20): List<Food>
    suspend fun getServings(foodId: String): List<FoodServing>
}

class SupabaseFoodRepository(private val client: SupabaseClient) : FoodRepository {

    override suspend fun findByBarcode(barcode: String): Food? {
        if (barcode.isBlank()) return null
        return client.postgrest.from("foods").select {
            filter { eq("barcode", barcode) }
            limit(1)
        }.decodeSingleOrNull<Food>()
    }

    override suspend fun search(query: String, limit: Int): List<Food> {
        if (query.isBlank()) return emptyList()
        val pattern = SearchPatterns.ilikePattern(query)
        return client.postgrest.from("foods").select {
            filter {
                or {
                    ilike("name", pattern)
                    ilike("brand", pattern)
                }
            }
            order("name", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeList<Food>()
    }

    override suspend fun getServings(foodId: String): List<FoodServing> {
        return client.postgrest.from("food_servings").select {
            filter { eq("food_id", foodId) }
            order("sort_order", Order.ASCENDING)
        }.decodeList<FoodServing>()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/macroplus/app/data/FoodRepository.kt
git commit -m "feat: add FoodRepository for barcode lookup and name/brand search"
```

---

### Task 5: CustomFood models and repository

**Files:**
- Create: `app/src/main/java/com/macroplus/app/data/model/CustomFoodModels.kt`
- Create: `app/src/main/java/com/macroplus/app/data/CustomFoodRepository.kt`

**Interfaces:**
- Consumes: `SupabaseClient`, `Auth` plugin (`client.auth.currentUserOrNull()?.id`)
- Produces: `CustomFood` (decode model), `NewCustomFood` (insert payload, no `id`/timestamps), `CustomFoodRepository` interface with `suspend fun list(): List<CustomFood>`, `suspend fun create(food: NewCustomFood): CustomFood`, `suspend fun delete(id: String)`. Task 8 wires this into `AppContainer`.

**No unit test in this task**, for the same reason as Task 4 — it's a thin Postgrest wrapper with no live project to verify against here.

- [ ] **Step 1: Write the models**

```kotlin
package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.custom_foods`. */
@Serializable
data class CustomFood(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") val servingQty: Double,
    @SerialName("serving_unit") val servingUnit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
)

/** Insert payload for a new custom food. `user_id` is filled in by the repository from the current session, never trusted from the caller. */
@Serializable
data class NewCustomFood(
    @SerialName("user_id") val userId: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") val servingQty: Double,
    @SerialName("serving_unit") val servingUnit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
)
```

- [ ] **Step 2: Write the repository**

```kotlin
package com.macroplus.app.data

import com.macroplus.app.data.model.CustomFood
import com.macroplus.app.data.model.NewCustomFood
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface CustomFoodRepository {
    suspend fun list(): List<CustomFood>
    suspend fun create(name: String, brand: String?, servingQty: Double, servingUnit: String, calories: Double, proteinG: Double, carbsG: Double, fatG: Double, barcode: String? = null): CustomFood
    suspend fun delete(id: String)
}

class SupabaseCustomFoodRepository(private val client: SupabaseClient) : CustomFoodRepository {

    private fun requireUserId(): String =
        client.auth.currentUserOrNull()?.id
            ?: error("CustomFoodRepository used before a user session exists.")

    override suspend fun list(): List<CustomFood> {
        val userId = requireUserId()
        return client.postgrest.from("custom_foods").select {
            filter { eq("user_id", userId) }
            order("name", Order.ASCENDING)
        }.decodeList<CustomFood>()
    }

    override suspend fun create(
        name: String,
        brand: String?,
        servingQty: Double,
        servingUnit: String,
        calories: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        barcode: String?,
    ): CustomFood {
        val payload = NewCustomFood(
            userId = requireUserId(),
            name = name,
            brand = brand,
            barcode = barcode,
            servingQty = servingQty,
            servingUnit = servingUnit,
            calories = calories,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
        )
        return client.postgrest.from("custom_foods").insert(payload) { select() }.decodeSingle<CustomFood>()
    }

    override suspend fun delete(id: String) {
        val userId = requireUserId()
        client.postgrest.from("custom_foods").delete {
            filter {
                eq("id", id)
                eq("user_id", userId)
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macroplus/app/data/model/CustomFoodModels.kt app/src/main/java/com/macroplus/app/data/CustomFoodRepository.kt
git commit -m "feat: add CustomFoodRepository for user-owned custom foods"
```

---

### Task 6: Recipe models and repository

**Files:**
- Create: `app/src/main/java/com/macroplus/app/data/model/RecipeModels.kt`
- Create: `app/src/main/java/com/macroplus/app/data/RecipeRepository.kt`

**Interfaces:**
- Consumes: `SupabaseClient`, `client.auth.currentUserOrNull()?.id`
- Produces: `Recipe`, `RecipeItem` (decode models), `NewRecipe`, `NewRecipeItem` (insert payloads), `RecipeRepository` interface with `suspend fun list(): List<Recipe>`, `suspend fun create(name: String, description: String?, instructions: String?, servings: Double): Recipe`, `suspend fun addItem(recipeId: String, foodId: String?, customFoodId: String?, quantity: Double, unit: String, sortOrder: Int): RecipeItem`, `suspend fun getItems(recipeId: String): List<RecipeItem>`. Task 8 wires this into `AppContainer`.

**No unit test in this task** — same reasoning as Task 4/5.

- [ ] **Step 1: Write the models**

```kotlin
package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.recipes`. */
@Serializable
data class Recipe(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val servings: Double,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class NewRecipe(
    @SerialName("user_id") val userId: String,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val servings: Double,
)

/**
 * Mirrors `public.recipe_items`. The DB check constraint requires exactly
 * one of `food_id`/`custom_food_id` to be set — this is enforced by the
 * database, not re-validated here, so a bad insert fails loudly with a
 * Postgrest error instead of silently storing an invalid row.
 */
@Serializable
data class RecipeItem(
    val id: String,
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    val quantity: Double,
    val unit: String,
    @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
data class NewRecipeItem(
    @SerialName("recipe_id") val recipeId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    val quantity: Double,
    val unit: String,
    @SerialName("sort_order") val sortOrder: Int,
)
```

- [ ] **Step 2: Write the repository**

```kotlin
package com.macroplus.app.data

import com.macroplus.app.data.model.NewRecipe
import com.macroplus.app.data.model.NewRecipeItem
import com.macroplus.app.data.model.Recipe
import com.macroplus.app.data.model.RecipeItem
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface RecipeRepository {
    suspend fun list(): List<Recipe>
    suspend fun create(name: String, description: String?, instructions: String?, servings: Double): Recipe
    suspend fun addItem(recipeId: String, foodId: String?, customFoodId: String?, quantity: Double, unit: String, sortOrder: Int): RecipeItem
    suspend fun getItems(recipeId: String): List<RecipeItem>
}

class SupabaseRecipeRepository(private val client: SupabaseClient) : RecipeRepository {

    private fun requireUserId(): String =
        client.auth.currentUserOrNull()?.id
            ?: error("RecipeRepository used before a user session exists.")

    override suspend fun list(): List<Recipe> {
        val userId = requireUserId()
        return client.postgrest.from("recipes").select {
            filter { eq("user_id", userId) }
            order("name", Order.ASCENDING)
        }.decodeList<Recipe>()
    }

    override suspend fun create(name: String, description: String?, instructions: String?, servings: Double): Recipe {
        require(servings > 0) { "servings must be > 0 (matches the recipes.servings > 0 check constraint)" }
        val payload = NewRecipe(
            userId = requireUserId(),
            name = name,
            description = description,
            instructions = instructions,
            servings = servings,
        )
        return client.postgrest.from("recipes").insert(payload) { select() }.decodeSingle<Recipe>()
    }

    override suspend fun addItem(
        recipeId: String,
        foodId: String?,
        customFoodId: String?,
        quantity: Double,
        unit: String,
        sortOrder: Int,
    ): RecipeItem {
        require((foodId != null) != (customFoodId != null)) {
            "Exactly one of foodId or customFoodId must be set (matches the recipe_items check constraint)"
        }
        val payload = NewRecipeItem(
            recipeId = recipeId,
            foodId = foodId,
            customFoodId = customFoodId,
            quantity = quantity,
            unit = unit,
            sortOrder = sortOrder,
        )
        return client.postgrest.from("recipe_items").insert(payload) { select() }.decodeSingle<RecipeItem>()
    }

    override suspend fun getItems(recipeId: String): List<RecipeItem> {
        return client.postgrest.from("recipe_items").select {
            filter { eq("recipe_id", recipeId) }
            order("sort_order", Order.ASCENDING)
        }.decodeList<RecipeItem>()
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macroplus/app/data/model/RecipeModels.kt app/src/main/java/com/macroplus/app/data/RecipeRepository.kt
git commit -m "feat: add RecipeRepository for user recipes and recipe items"
```

---

### Task 7: Favorites and recent-foods

**Files:**
- Create: `app/src/main/java/com/macroplus/app/data/model/FavoriteModels.kt`
- Create: `app/src/main/java/com/macroplus/app/data/model/RecentLogModels.kt`
- Create: `app/src/main/java/com/macroplus/app/data/FavoritesRepository.kt`
- Create: `app/src/main/java/com/macroplus/app/data/RecentFoodRepository.kt`
- Create: `app/src/main/java/com/macroplus/app/domain/RecentReferences.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/RecentReferencesTest.kt`

**Interfaces:**
- Consumes: `SupabaseClient`, `client.auth.currentUserOrNull()?.id`
- Produces: `FoodFavorite` (decode), `NewFoodFavorite` (insert), `FavoritesRepository` with `suspend fun list(): List<FoodFavorite>`, `suspend fun addFood(foodId: String)`, `suspend fun removeFood(foodId: String)`; `RecentLogReference` (domain type: `foodId, customFoodId, recipeId, displayName, loggedAt`), `dedupeRecentReferences(entries: List<RecentLogReference>, limit: Int): List<RecentLogReference>` (pure function), `RecentFoodRepository` with `suspend fun getRecent(limit: Int = 10): List<RecentLogReference>`.

**Why recent-foods dedupes client-side:** PostgREST has no `DISTINCT ON` query parameter, so `RecentFoodRepository` fetches the most recent 50 `food_log_entries` rows and dedupes them down to the caller's requested limit, keeping only the first (most recent) occurrence of each food/custom-food/recipe. `food_log_entries` exists in the schema already but has no writer yet (the daily logger is a later slice per `CLAUDE.md`'s recommended order) — this repository will correctly return an empty list until that slice lands, which is expected, not a bug.

- [ ] **Step 1: Write the failing test for the dedupe function**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.RecentReferencesTest"`
Expected: FAIL — `RecentLogReference`/`dedupeRecentReferences` unresolved.

- [ ] **Step 3: Write the models**

```kotlin
// app/src/main/java/com/macroplus/app/data/model/FavoriteModels.kt
package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.food_favorites`. No `id` column — uniqueness is enforced by partial unique indexes in the migration. */
@Serializable
data class FoodFavorite(
    @SerialName("user_id") val userId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class NewFoodFavorite(
    @SerialName("user_id") val userId: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
)
```

```kotlin
// app/src/main/java/com/macroplus/app/data/model/RecentLogModels.kt
package com.macroplus.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raw row shape selected from `food_log_entries` for the recent-foods query. */
@Serializable
data class RecentLogEntryRow(
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: String,
)
```

- [ ] **Step 4: Write the pure dedupe function**

```kotlin
package com.macroplus.app.domain

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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.RecentReferencesTest"`
Expected: PASS

- [ ] **Step 6: Write the repositories (no unit test — thin Postgrest wrappers, see Task 4's note)**

```kotlin
// app/src/main/java/com/macroplus/app/data/FavoritesRepository.kt
package com.macroplus.app.data

import com.macroplus.app.data.model.FoodFavorite
import com.macroplus.app.data.model.NewFoodFavorite
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

interface FavoritesRepository {
    suspend fun list(): List<FoodFavorite>
    suspend fun addFood(foodId: String)
    suspend fun removeFood(foodId: String)
}

class SupabaseFavoritesRepository(private val client: SupabaseClient) : FavoritesRepository {

    private fun requireUserId(): String =
        client.auth.currentUserOrNull()?.id
            ?: error("FavoritesRepository used before a user session exists.")

    override suspend fun list(): List<FoodFavorite> {
        val userId = requireUserId()
        return client.postgrest.from("food_favorites").select {
            filter { eq("user_id", userId) }
            order("sort_order", Order.ASCENDING)
        }.decodeList<FoodFavorite>()
    }

    override suspend fun addFood(foodId: String) {
        val payload = NewFoodFavorite(userId = requireUserId(), foodId = foodId)
        client.postgrest.from("food_favorites").insert(payload)
    }

    override suspend fun removeFood(foodId: String) {
        val userId = requireUserId()
        client.postgrest.from("food_favorites").delete {
            filter {
                eq("user_id", userId)
                eq("food_id", foodId)
            }
        }
    }
}
```

```kotlin
// app/src/main/java/com/macroplus/app/data/RecentFoodRepository.kt
package com.macroplus.app.data

import com.macroplus.app.data.model.RecentLogEntryRow
import com.macroplus.app.domain.RecentLogReference
import com.macroplus.app.domain.dedupeRecentReferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

private const val FETCH_BUFFER = 50L

interface RecentFoodRepository {
    suspend fun getRecent(limit: Int = 10): List<RecentLogReference>
}

class SupabaseRecentFoodRepository(private val client: SupabaseClient) : RecentFoodRepository {

    override suspend fun getRecent(limit: Int): List<RecentLogReference> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: error("RecentFoodRepository used before a user session exists.")
        val rows = client.postgrest.from("food_log_entries").select {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
            limit(FETCH_BUFFER)
        }.decodeList<RecentLogEntryRow>()

        val references = rows.map {
            RecentLogReference(
                foodId = it.foodId,
                customFoodId = it.customFoodId,
                recipeId = it.recipeId,
                displayName = it.displayName,
                loggedAt = it.createdAt,
            )
        }
        return dedupeRecentReferences(references, limit)
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/macroplus/app/data/model/FavoriteModels.kt \
        app/src/main/java/com/macroplus/app/data/model/RecentLogModels.kt \
        app/src/main/java/com/macroplus/app/data/FavoritesRepository.kt \
        app/src/main/java/com/macroplus/app/data/RecentFoodRepository.kt \
        app/src/main/java/com/macroplus/app/domain/RecentReferences.kt \
        app/src/test/java/com/macroplus/app/domain/RecentReferencesTest.kt
git commit -m "feat: add FavoritesRepository and RecentFoodRepository"
```

---

### Task 8: AppContainer wiring

**Files:**
- Create: `app/src/main/java/com/macroplus/app/data/AppContainer.kt`

**Interfaces:**
- Consumes: `SupabaseClientProvider.create()` (existing), every repository from Tasks 4–7
- Produces: `AppContainer` class exposing `foodRepository: FoodRepository`, `customFoodRepository: CustomFoodRepository`, `recipeRepository: RecipeRepository`, `favoritesRepository: FavoritesRepository`, `recentFoodRepository: RecentFoodRepository`. Future ViewModels (a later slice) construct their repository dependencies from this container instead of calling `SupabaseClientProvider` directly.

**No unit test** — this is dependency wiring with no branching logic to test.

- [ ] **Step 1: Write the implementation**

```kotlin
package com.macroplus.app.data

class AppContainer {
    private val client by lazy { SupabaseClientProvider.create() }

    val foodRepository: FoodRepository by lazy { SupabaseFoodRepository(client) }
    val customFoodRepository: CustomFoodRepository by lazy { SupabaseCustomFoodRepository(client) }
    val recipeRepository: RecipeRepository by lazy { SupabaseRecipeRepository(client) }
    val favoritesRepository: FavoritesRepository by lazy { SupabaseFavoritesRepository(client) }
    val recentFoodRepository: RecentFoodRepository by lazy { SupabaseRecentFoodRepository(client) }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/macroplus/app/data/AppContainer.kt
git commit -m "feat: wire food repository layer into AppContainer"
```

---

## Self-Review

**Spec coverage** (against CLAUDE.md step 2 — "exact barcode lookup, name/brand search, serving scaling, favorites, recent history, custom foods, and recipes"):
- Exact barcode lookup → Task 4 (`findByBarcode`)
- Name/brand search → Task 4 (`search`, using Task 3's escaped `ilike` pattern)
- Serving scaling → Task 2 (`ServingScaler`)
- Favorites → Task 7 (`FavoritesRepository`)
- Recent history → Task 7 (`RecentFoodRepository`)
- Custom foods → Task 5
- Recipes → Task 6

All seven items have a task. Nothing in this slice covers the daily logger, the adaptive engine port, or barcode camera UI — that's intentional; those are separate later slices per `CLAUDE.md`'s recommended order and out of scope here.

**Placeholder scan:** no TODO/TBD/"add appropriate handling" strings in any task; every code block is complete, compilable Kotlin (verified DSL calls against the real `postgrest-kt`/`auth-kt` 3.7.0 sources, not guessed from memory).

**Type consistency:** `Food.servingQty`/`servingUnit` (Task 1) are what `ServingScaler.scale`/`scaleByServing` (Task 2) scale against — checked against `import_openfoodfacts.py`'s `make_row` to confirm calories are stored per `serving_qty`/`serving_unit`, not per `nutrition_basis_qty`/`nutrition_basis_unit` (an earlier draft of this plan had that backwards; fixed before finalizing). `RecentLogReference` fields match between Task 7's model, its dedupe function, and its test. Repository method signatures referenced from `AppContainer` (Task 8) match each repository's declared interface exactly.

**Known gap, tracked not hidden:** Tasks 4–8's Postgrest-backed methods have no automated test coverage in this plan because there's no live Supabase project reachable from this sandbox. That's a real gap, not a rounding error — before this slice is considered done, run the manual verification steps in Task 4 against a real project, or add an integration test suite (a new task, out of scope for this plan) once one exists.
