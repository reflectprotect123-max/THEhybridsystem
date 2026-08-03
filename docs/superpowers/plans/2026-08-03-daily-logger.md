# Daily Logger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android data-layer repositories that let a user log food against a date, using nutrition values snapshotted at log time so later edits to a food/custom food never rewrite history.

**Architecture:** Two new repositories (`LogRepository`, `DayStatusRepository`) sit on top of the food-repository slice already committed on this branch. All macro math is delegated to `ServingScaler` (broadened to scale both `Food` and `CustomFood` via a shared `Scalable` interface) plus a new pure `MacroResolution` object for the two things `ServingScaler` alone can't do: falling back to a `FoodServing` conversion when a logged unit doesn't match a food's stored serving unit, and rolling up a recipe's ingredients into a per-serving macro profile.

**Tech Stack:** Kotlin, `io.github.jan-tennert.supabase:postgrest-kt`/`auth-kt` 3.7.0 (same verified API surface as the food-repository slice), `kotlinx.serialization.json`, `java.time.LocalDate` (native on minSdk 26, no desugaring needed), JUnit 4.

## Global Constraints

Copied verbatim from `CLAUDE.md` and `docs/ADAPTIVE_ENGINE_CONTRACT.md`, binding every task below:

- Never turn an unlogged or partial day into zero calories. A day with zero `food_log_entries` rows must read as "unlogged" (`getDailyTotals` returns `null`), never as a functional zero.
- A declared fast is countable only when the app stores zero calories **explicitly**. This plan does not build the "declare a fast" UI action (that's a later slice), but `logQuickAdd` with all-zero macros is the primitive that action will use — verified end-to-end reachable, not hidden behind a gap.
- Preserve source provenance and the original nutrient profile: snapshot columns (`calories`/`protein_g`/`carbs_g`/`fat_g`/`display_name`) are the historical record. `food_id`/`custom_food_id`/`recipe_id` are for reference only — never re-joined to recompute a displayed total.
- Keep the service-role Supabase key out of Android — every repository uses the existing `SupabaseClient` from `SupabaseClientProvider` (anon/publishable key + RLS), never a privileged client.
- Never invent nutrition numbers, serving weights, or unit conversions. `MacroResolution.resolveFoodMacros` must throw rather than guess, exactly like `ServingScaler` already does.
- minSdk is 26 (`app/build.gradle.kts`) — `java.time.LocalDate`/`Instant` are usable directly, no `coreLibraryDesugaring` needed.

**Lesson carried from the food-repository slice's final review (applies to every new repository below from the start, not as a later fix):**
- Every `requireUserId()` must call `client.auth.awaitInitialization()` before `client.auth.currentUserOrNull()` — `currentUserOrNull()` returns `null` during the async session-restore race on cold start even for a signed-in user, and `awaitInitialization()` is the verified real fix (confirmed against the actual `auth-kt` 3.7.0 sources: `suspend fun awaitInitialization()`, no args).
- Any query against a table with a `deleted_at` column must filter it explicitly (`exact("deleted_at", null)`) — don't rely on a view to do it for you unless you're actually querying that view.

---

## File Structure

```
app/src/main/java/com/macrotrack/app/
  domain/
    Scalable.kt              # NEW — interface both Food and CustomFood implement
    ServingScaler.kt         # MODIFY — scale()/scaleByServing() take Scalable, not Food
    MacroResolution.kt       # NEW — pure: serving-fallback resolution + recipe rollup math
  data/
    model/
      FoodModels.kt          # MODIFY — Food implements Scalable
      CustomFoodModels.kt    # MODIFY — CustomFood implements Scalable
      LogEntryModels.kt      # NEW — FoodLogEntry, NewFoodLogEntry, DailyTotals, EntryKind, Meal
      DayStatusModels.kt     # NEW — DailyLogStatus, DayStatus
    FoodRepository.kt        # MODIFY — add getById(id): Food?
    CustomFoodRepository.kt  # MODIFY — add getById(id): CustomFood?
    RecipeRepository.kt      # MODIFY — add getById(id): Recipe?
    RecipeMacroResolver.kt   # NEW — I/O: resolves a recipe's per-serving macros
    LogRepository.kt         # NEW — the daily logger itself
    DayStatusRepository.kt   # NEW — get/set daily_log_status
    AppContainer.kt          # MODIFY — wire the two new repositories
app/src/test/java/com/macrotrack/app/
  domain/
    ServingScalerTest.kt     # MODIFY — add a CustomFood-scaling test case
    MacroResolutionTest.kt   # NEW
  data/model/
    LogEntryModelsTest.kt    # NEW
    DayStatusModelsTest.kt   # NEW
```

---

### Task 1: Broaden ServingScaler to scale both Food and CustomFood

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/domain/Scalable.kt`
- Modify: `app/src/main/java/com/macrotrack/app/domain/ServingScaler.kt`
- Modify: `app/src/main/java/com/macrotrack/app/data/model/FoodModels.kt`
- Modify: `app/src/main/java/com/macrotrack/app/data/model/CustomFoodModels.kt`
- Test: `app/src/test/java/com/macrotrack/app/domain/ServingScalerTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `Scalable` interface (`id, name, servingQty, servingUnit, calories, proteinG, carbsG, fatG`); `Food` and `CustomFood` both implement it; `ServingScaler.scale(food: Scalable, ...)`/`scaleByServing(food: Scalable, ...)`. Every later task that scales a `CustomFood` (Task 6's `logCustomFood`, Task 5's recipe resolver) depends on this.

**Why:** a recipe's ingredients, and a direct food log entry, both need the exact same "scale stored macros to a requested quantity, never guess a conversion" logic `ServingScaler` already has for `Food` — but `CustomFood` needs it too (a user logs "200 g of my protein shake"), and duplicating the scaling math into a second copy for `CustomFood` would violate DRY and risk the two copies drifting. `Food` and `CustomFood` already share the exact same 8 field names (`id, name, servingQty, servingUnit, calories, proteinG, carbsG, fatG`) — pulling those into an interface costs nothing and both classes need zero constructor changes, only an `override` keyword on the 8 matching properties.

- [ ] **Step 1: Write the failing test**

Add this test to the existing `ServingScalerTest.kt` (append it inside the existing `class ServingScalerTest`, don't remove any existing test):

```kotlin
    @Test
    fun scalesACustomFoodTheSameWayAsAFood() {
        val customShake = com.macrotrack.app.data.model.CustomFood(
            id = "custom-1",
            userId = "user-1",
            name = "My Protein Shake",
            brand = null,
            barcode = null,
            servingQty = 250.0,
            servingUnit = "ml",
            calories = 180.0,
            proteinG = 30.0,
            carbsG = 9.0,
            fatG = 2.0,
        )

        val result = ServingScaler.scale(customShake, quantity = 500.0, unit = "ml")

        assertEquals(360.0, result.calories, 0.001)
        assertEquals(60.0, result.proteinG, 0.001)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.ServingScalerTest"`
Expected: FAIL — compile error, `ServingScaler.scale` does not accept a `CustomFood` argument (it's typed to `Food`).

- [ ] **Step 3: Write the Scalable interface**

```kotlin
package com.macrotrack.app.domain

/**
 * Anything ServingScaler can scale: a row whose macros are stored per
 * `servingQty` of `servingUnit`. Food and CustomFood both implement this —
 * see ServingScaler's KDoc for why cross-unit conversion is never guessed.
 */
interface Scalable {
    val id: String
    val name: String
    val servingQty: Double
    val servingUnit: String
    val calories: Double
    val proteinG: Double
    val carbsG: Double
    val fatG: Double
}
```

- [ ] **Step 4: Retrofit Food and CustomFood to implement Scalable**

In `app/src/main/java/com/macrotrack/app/data/model/FoodModels.kt`, change the `Food` class declaration and add `override` to the 8 shared properties (every other property — `brand`, `barcode`, `source`, `externalId`, `nutritionBasisQty`, `nutritionBasisUnit`, `servingSizeText` — is unchanged):

```kotlin
import com.macrotrack.app.domain.Scalable

@Serializable
data class Food(
    override val id: String,
    override val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") override val servingQty: Double,
    @SerialName("serving_unit") override val servingUnit: String,
    override val calories: Double,
    @SerialName("protein_g") override val proteinG: Double,
    @SerialName("carbs_g") override val carbsG: Double,
    @SerialName("fat_g") override val fatG: Double,
    val source: String,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("nutrition_basis_qty") val nutritionBasisQty: Double,
    @SerialName("nutrition_basis_unit") val nutritionBasisUnit: String,
    @SerialName("serving_size_text") val servingSizeText: String? = null,
) : Scalable
```

(Leave `FoodServing` and the file's existing KDoc comment untouched — only `Food`'s class header and its 8 matching properties change.)

In `app/src/main/java/com/macrotrack/app/data/model/CustomFoodModels.kt`, the same treatment for `CustomFood` only (leave `NewCustomFood` untouched — it's an insert payload, not something that ever gets scaled):

```kotlin
import com.macrotrack.app.domain.Scalable

@Serializable
data class CustomFood(
    override val id: String,
    @SerialName("user_id") val userId: String,
    override val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_qty") override val servingQty: Double,
    @SerialName("serving_unit") override val servingUnit: String,
    override val calories: Double,
    @SerialName("protein_g") override val proteinG: Double,
    @SerialName("carbs_g") override val carbsG: Double,
    @SerialName("fat_g") override val fatG: Double,
) : Scalable
```

- [ ] **Step 5: Update ServingScaler's signatures**

In `app/src/main/java/com/macrotrack/app/domain/ServingScaler.kt`, change every `food: Food` parameter to `food: Scalable` (there are three: `scale`, `scaleByServing`, the private `scaleByMultiplier`), and drop the now-unused `import com.macrotrack.app.data.model.Food`:

```kotlin
package com.macrotrack.app.domain

import com.macrotrack.app.data.model.FoodServing

data class ScaledMacros(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

/**
 * Scales a Scalable's stored macros (which are per `food.servingQty` of
 * `food.servingUnit`) to a requested quantity. Never converts between mass
 * and volume (e.g. g <-> ml) by guessing a density — CLAUDE.md's
 * non-negotiable rule #1. A cross-unit conversion is only allowed via an
 * explicit FoodServing row that records the real grams/millilitres for that
 * serving, because that value came from the source data, not a guess.
 */
object ServingScaler {
    class IncompatibleUnitException(message: String) : Exception(message)

    fun scale(food: Scalable, quantity: Double, unit: String): ScaledMacros {
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

    fun scaleByServing(food: Scalable, serving: FoodServing, servingCount: Double = 1.0): ScaledMacros {
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

    private fun scaleByMultiplier(food: Scalable, multiplier: Double) = ScaledMacros(
        calories = food.calories * multiplier,
        proteinG = food.proteinG * multiplier,
        carbsG = food.carbsG * multiplier,
        fatG = food.fatG * multiplier,
    )
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.ServingScalerTest"`
Expected: PASS (6 tests — the 5 existing plus the new `CustomFood` one). All 5 existing tests pass unchanged because `Food` still satisfies `Scalable`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/domain/Scalable.kt \
        app/src/main/java/com/macrotrack/app/domain/ServingScaler.kt \
        app/src/main/java/com/macrotrack/app/data/model/FoodModels.kt \
        app/src/main/java/com/macrotrack/app/data/model/CustomFoodModels.kt \
        app/src/test/java/com/macrotrack/app/domain/ServingScalerTest.kt
git commit -m "feat: broaden ServingScaler to scale both Food and CustomFood via Scalable"
```

---

### Task 2: Log entry models

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/model/LogEntryModels.kt`
- Test: `app/src/test/java/com/macrotrack/app/data/model/LogEntryModelsTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `FoodLogEntry` (decode model for `food_log_entries`), `NewFoodLogEntry` (insert payload), `DailyTotals` (decode model for the `daily_nutrition_totals` view), `EntryKind` (constants: `FOOD`, `CUSTOM_FOOD`, `RECIPE`, `QUICK_ADD` — matching the DB check constraint's exact values), `Meal` (suggested-value constants: `BREAKFAST`, `LUNCH`, `DINNER`, `SNACK`, `OTHER` — `food_log_entries.meal` has no DB check constraint, so these are conventions, not enforced). Task 6's `LogRepository` produces/consumes these.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogEntryModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAFoodLogEntryRow() {
        val row = """
            {
              "id": "entry-1",
              "user_id": "user-1",
              "log_date": "2026-08-03",
              "meal": "lunch",
              "entry_kind": "food",
              "food_id": "food-1",
              "custom_food_id": null,
              "recipe_id": null,
              "quantity": 150,
              "unit": "g",
              "calories": 248,
              "protein_g": 46.5,
              "carbs_g": 0,
              "fat_g": 5.4,
              "display_name": "Grilled Chicken Breast",
              "notes": null
            }
        """.trimIndent()

        val entry = json.decodeFromString(FoodLogEntry.serializer(), row)

        assertEquals("lunch", entry.meal)
        assertEquals(EntryKind.FOOD, entry.entryKind)
        assertEquals("food-1", entry.foodId)
        assertNull(entry.customFoodId)
        assertEquals(248.0, entry.calories, 0.001)
        assertEquals("Grilled Chicken Breast", entry.displayName)
    }

    @Test
    fun decodesDailyTotalsFromTheView() {
        val row = """
            {
              "user_id": "user-1",
              "log_date": "2026-08-03",
              "calories": 1840,
              "protein_g": 132,
              "carbs_g": 190,
              "fat_g": 58,
              "entry_count": 7
            }
        """.trimIndent()

        val totals = json.decodeFromString(DailyTotals.serializer(), row)

        assertEquals(1840.0, totals.calories, 0.001)
        assertEquals(7, totals.entryCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.LogEntryModelsTest"`
Expected: FAIL — `FoodLogEntry`/`DailyTotals`/`EntryKind` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Values `food_log_entries.entry_kind`'s DB check constraint allows — copied verbatim, not re-derived. */
object EntryKind {
    const val FOOD = "food"
    const val CUSTOM_FOOD = "custom_food"
    const val RECIPE = "recipe"
    const val QUICK_ADD = "quick_add"
}

/** Suggested `food_log_entries.meal` values. Not DB-enforced — the column is free text. */
object Meal {
    const val BREAKFAST = "breakfast"
    const val LUNCH = "lunch"
    const val DINNER = "dinner"
    const val SNACK = "snack"
    const val OTHER = "other"
}

/**
 * Mirrors `public.food_log_entries`. calories/protein_g/carbs_g/fat_g and
 * display_name are the SNAPSHOT taken at log time — never re-derived from
 * food_id/custom_food_id/recipe_id after the fact, so editing a food later
 * does not rewrite history (CLAUDE.md rule #2/#3).
 */
@Serializable
data class FoodLogEntry(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val meal: String,
    @SerialName("entry_kind") val entryKind: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    val quantity: Double,
    val unit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("display_name") val displayName: String,
    val notes: String? = null,
)

/** Insert payload. `user_id` is filled in by the repository from the session, never trusted from the caller. */
@Serializable
data class NewFoodLogEntry(
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val meal: String,
    @SerialName("entry_kind") val entryKind: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("custom_food_id") val customFoodId: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    val quantity: Double,
    val unit: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("display_name") val displayName: String,
    val notes: String? = null,
)

/**
 * Mirrors the `public.daily_nutrition_totals` view. The view's own SQL
 * (`group by user_id, log_date`) means a date with zero entries simply has
 * no row — callers get `null` from `decodeSingleOrNull`, never a zeroed
 * DailyTotals. Do not construct a synthetic zero DailyTotals anywhere.
 */
@Serializable
data class DailyTotals(
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("entry_count") val entryCount: Int,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.LogEntryModelsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/model/LogEntryModels.kt app/src/test/java/com/macrotrack/app/data/model/LogEntryModelsTest.kt
git commit -m "feat: add food log entry and daily totals models"
```

---

### Task 3: Day status models

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/model/DayStatusModels.kt`
- Test: `app/src/test/java/com/macrotrack/app/data/model/DayStatusModelsTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `DailyLogStatus` (decode + upsert payload for `daily_log_status` — the table has no `id` and no separate insert-payload shape is needed), `DayStatus` (constants: `COMPLETE`, `PARTIAL`, `FASTED`, `UNLOGGED` — matching the DB check constraint exactly). Task 7's `DayStatusRepository` consumes these.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayStatusModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesADailyLogStatusRow() {
        val row = """
            {
              "user_id": "user-1",
              "log_date": "2026-08-03",
              "status": "complete",
              "note": null
            }
        """.trimIndent()

        val status = json.decodeFromString(DailyLogStatus.serializer(), row)

        assertEquals(DayStatus.COMPLETE, status.status)
        assertNull(status.note)
    }

    @Test
    fun dayStatusConstantsMatchTheDbCheckConstraintValues() {
        // supabase/migrations/001_macro_foundation.sql:
        // status text not null check (status in ('complete', 'partial', 'fasted', 'unlogged'))
        assertEquals("complete", DayStatus.COMPLETE)
        assertEquals("partial", DayStatus.PARTIAL)
        assertEquals("fasted", DayStatus.FASTED)
        assertEquals("unlogged", DayStatus.UNLOGGED)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.DayStatusModelsTest"`
Expected: FAIL — `DailyLogStatus`/`DayStatus` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Values `daily_log_status.status`'s DB check constraint allows — copied verbatim, not re-derived. */
object DayStatus {
    const val COMPLETE = "complete"
    const val PARTIAL = "partial"
    const val FASTED = "fasted"
    const val UNLOGGED = "unlogged"
}

/**
 * Mirrors `public.daily_log_status`. Primary key is (user_id, log_date), so
 * this same shape works for both decode and upsert — there's no separate
 * `id` column to omit on insert.
 *
 * A missing row for a date and an explicit `status = "unlogged"` row both
 * mean "nothing declared for this day" in practice — DayStatusRepository's
 * `getStatus` returns null for the former; callers should treat null and
 * an explicit UNLOGGED status the same way unless they specifically care
 * about the difference between "never asked" and "explicitly marked".
 */
@Serializable
data class DailyLogStatus(
    @SerialName("user_id") val userId: String,
    @SerialName("log_date") val logDate: String,
    val status: String,
    val note: String? = null,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.DayStatusModelsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/model/DayStatusModels.kt app/src/test/java/com/macrotrack/app/data/model/DayStatusModelsTest.kt
git commit -m "feat: add daily log status model"
```

---

### Task 4: MacroResolution (pure serving-fallback and recipe rollup math)

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/domain/MacroResolution.kt`
- Test: `app/src/test/java/com/macrotrack/app/domain/MacroResolutionTest.kt`

**Interfaces:**
- Consumes: `ScaledMacros`, `ServingScaler` (Task 1), `Food`/`FoodServing` (already committed)
- Produces: `MacroResolution.resolveFoodMacros(food: Food, servings: List<FoodServing>, quantity: Double, unit: String): ScaledMacros`, `MacroResolution.sumMacros(items: List<ScaledMacros>): ScaledMacros`, `MacroResolution.perServing(total: ScaledMacros, recipeServings: Double): ScaledMacros`, `MacroResolution.forLoggedServings(perServingMacros: ScaledMacros, loggedServings: Double): ScaledMacros`. Task 5's `RecipeMacroResolver` and Task 6's `LogRepository.logFood` both call these.

**Why `resolveFoodMacros` exists separately from `ServingScaler.scale`:** a user (or a recipe ingredient) might log a food in a unit that doesn't match the food's stored `servingUnit` directly — e.g. the food is stored per 100 g, but the log entry is "1 cup". `ServingScaler.scale` correctly throws rather than guess a g↔cup conversion; `resolveFoodMacros` is what tries the exact-unit path first, and only falls back to an explicit `FoodServing` conversion (which carries a real, source-derived gram/millilitre value) if one exists for that exact unit. If neither resolves, it still throws — this function never invents a conversion either, it just tries one more legitimate source before giving up.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macrotrack.app.domain

import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.FoodServing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MacroResolutionTest {
    private val oats = Food(
        id = "food-1", name = "Rolled Oats", brand = null, barcode = null,
        servingQty = 100.0, servingUnit = "g", calories = 379.0, proteinG = 13.2,
        carbsG = 67.7, fatG = 6.9, source = "ausnut", externalId = "12-345",
        nutritionBasisQty = 100.0, nutritionBasisUnit = "g", servingSizeText = "100 g",
    )
    private val cupServing = FoodServing(
        id = "serving-1", foodId = "food-1", label = "1 cup", quantity = 1.0,
        unit = "cup", grams = 90.0, millilitres = null, isDefault = true, sortOrder = 0,
    )

    @Test
    fun resolvesDirectlyWhenTheUnitMatchesTheFoodsServingUnit() {
        val result = MacroResolution.resolveFoodMacros(oats, servings = listOf(cupServing), quantity = 50.0, unit = "g")
        assertEquals(189.5, result.calories, 0.001)
    }

    @Test
    fun fallsBackToAMatchingFoodServingWhenTheUnitDoesNotMatch() {
        // 2 cups = 180 g -> 1.8x the 100g-basis macros, same math ServingScaler.scaleByServing already proved
        val result = MacroResolution.resolveFoodMacros(oats, servings = listOf(cupServing), quantity = 2.0, unit = "cup")
        assertEquals(682.2, result.calories, 0.001)
    }

    @Test
    fun throwsWhenNeitherTheDirectUnitNorAServingMatches() {
        assertThrows(ServingScaler.IncompatibleUnitException::class.java) {
            MacroResolution.resolveFoodMacros(oats, servings = listOf(cupServing), quantity = 1.0, unit = "tablespoon")
        }
    }

    @Test
    fun sumsMacrosAcrossItems() {
        val a = ScaledMacros(calories = 100.0, proteinG = 10.0, carbsG = 5.0, fatG = 2.0)
        val b = ScaledMacros(calories = 50.0, proteinG = 2.0, carbsG = 8.0, fatG = 1.0)
        val result = MacroResolution.sumMacros(listOf(a, b))
        assertEquals(150.0, result.calories, 0.001)
        assertEquals(12.0, result.proteinG, 0.001)
    }

    @Test
    fun dividesByRecipeServingsForPerServingMacros() {
        val total = ScaledMacros(calories = 900.0, proteinG = 60.0, carbsG = 90.0, fatG = 30.0)
        val result = MacroResolution.perServing(total, recipeServings = 3.0)
        assertEquals(300.0, result.calories, 0.001)
        assertEquals(20.0, result.proteinG, 0.001)
    }

    @Test
    fun multipliesPerServingByLoggedServings() {
        val perServing = ScaledMacros(calories = 300.0, proteinG = 20.0, carbsG = 30.0, fatG = 10.0)
        val result = MacroResolution.forLoggedServings(perServing, loggedServings = 1.5)
        assertEquals(450.0, result.calories, 0.001)
        assertEquals(30.0, result.proteinG, 0.001)
    }

    @Test
    fun rejectsZeroOrNegativeRecipeServings() {
        val total = ScaledMacros(100.0, 10.0, 10.0, 5.0)
        assertThrows(IllegalArgumentException::class.java) { MacroResolution.perServing(total, recipeServings = 0.0) }
    }

    @Test
    fun rejectsZeroOrNegativeLoggedServings() {
        val perServing = ScaledMacros(100.0, 10.0, 10.0, 5.0)
        assertThrows(IllegalArgumentException::class.java) { MacroResolution.forLoggedServings(perServing, loggedServings = -1.0) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.MacroResolutionTest"`
Expected: FAIL — `MacroResolution` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macrotrack.app.domain

import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.FoodServing

/**
 * Pure macro math that ServingScaler alone can't do: falling back to an
 * explicit FoodServing conversion when a logged unit doesn't match a
 * food's stored serving unit, and rolling a recipe's ingredients up into
 * per-serving macros. No I/O — callers (LogRepository, RecipeMacroResolver)
 * fetch the Food/FoodServing rows and pass them in already-resolved.
 */
object MacroResolution {

    fun resolveFoodMacros(food: Food, servings: List<FoodServing>, quantity: Double, unit: String): ScaledMacros {
        return try {
            ServingScaler.scale(food, quantity, unit)
        } catch (direct: ServingScaler.IncompatibleUnitException) {
            val matching = servings.firstOrNull { it.unit.equals(unit, ignoreCase = true) }
                ?: throw direct
            val servingCount = quantity / matching.quantity
            ServingScaler.scaleByServing(food, matching, servingCount)
        }
    }

    fun sumMacros(items: List<ScaledMacros>): ScaledMacros =
        items.fold(ScaledMacros(0.0, 0.0, 0.0, 0.0)) { acc, item ->
            ScaledMacros(
                calories = acc.calories + item.calories,
                proteinG = acc.proteinG + item.proteinG,
                carbsG = acc.carbsG + item.carbsG,
                fatG = acc.fatG + item.fatG,
            )
        }

    fun perServing(total: ScaledMacros, recipeServings: Double): ScaledMacros {
        require(recipeServings > 0) { "recipeServings must be > 0, got $recipeServings" }
        return ScaledMacros(
            calories = total.calories / recipeServings,
            proteinG = total.proteinG / recipeServings,
            carbsG = total.carbsG / recipeServings,
            fatG = total.fatG / recipeServings,
        )
    }

    fun forLoggedServings(perServingMacros: ScaledMacros, loggedServings: Double): ScaledMacros {
        require(loggedServings > 0) { "loggedServings must be > 0, got $loggedServings" }
        return ScaledMacros(
            calories = perServingMacros.calories * loggedServings,
            proteinG = perServingMacros.proteinG * loggedServings,
            carbsG = perServingMacros.carbsG * loggedServings,
            fatG = perServingMacros.fatG * loggedServings,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.MacroResolutionTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/domain/MacroResolution.kt app/src/test/java/com/macrotrack/app/domain/MacroResolutionTest.kt
git commit -m "feat: add MacroResolution for serving-fallback and recipe rollup math"
```

---

### Task 5: getById additions + RecipeMacroResolver

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/FoodRepository.kt`
- Modify: `app/src/main/java/com/macrotrack/app/data/CustomFoodRepository.kt`
- Modify: `app/src/main/java/com/macrotrack/app/data/RecipeRepository.kt`
- Create: `app/src/main/java/com/macrotrack/app/data/RecipeMacroResolver.kt`

**Interfaces:**
- Consumes: `FoodRepository`/`CustomFoodRepository`/`RecipeRepository` (existing), `MacroResolution`/`ServingScaler` (Task 4/1)
- Produces: `FoodRepository.getById(id: String): Food?`, `CustomFoodRepository.getById(id: String): CustomFood?`, `RecipeRepository.getById(id: String): Recipe?`, `RecipeMacroResolver.resolvePerServingMacros(recipe: Recipe): ScaledMacros`. Task 6's `LogRepository.logRecipeServings` consumes `RecipeMacroResolver` and `RecipeRepository.getById`.

**No unit test in this task** — same reasoning as the food-repository slice's thin Postgrest wrappers: no live Supabase project reachable from this sandbox to test against. Manually verify: `getById` calls follow the exact `findByBarcode`/`eq("id", id)`/`limit(1)`/`decodeSingleOrNull` pattern already reviewed and approved in `FoodRepository.kt`.

- [ ] **Step 1: Add getById to FoodRepository**

In `app/src/main/java/com/macrotrack/app/data/FoodRepository.kt`, add to the `FoodRepository` interface:

```kotlin
    suspend fun getById(id: String): Food?
```

And to `SupabaseFoodRepository`:

```kotlin
    override suspend fun getById(id: String): Food? {
        return client.postgrest.from("foods").select {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull<Food>()
    }
```

- [ ] **Step 2: Add getById to CustomFoodRepository**

In `app/src/main/java/com/macrotrack/app/data/CustomFoodRepository.kt`, add to the `CustomFoodRepository` interface:

```kotlin
    suspend fun getById(id: String): CustomFood?
```

And to `SupabaseCustomFoodRepository` (no extra `user_id` filter needed — RLS's `custom_food_owner_all` policy already restricts reads to the row's owner, exactly as it does for `list()`):

```kotlin
    override suspend fun getById(id: String): CustomFood? {
        return client.postgrest.from("custom_foods").select {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull<CustomFood>()
    }
```

- [ ] **Step 3: Add getById to RecipeRepository**

In `app/src/main/java/com/macrotrack/app/data/RecipeRepository.kt`, add to the `RecipeRepository` interface:

```kotlin
    suspend fun getById(id: String): Recipe?
```

And to `SupabaseRecipeRepository`:

```kotlin
    override suspend fun getById(id: String): Recipe? {
        return client.postgrest.from("recipes").select {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull<Recipe>()
    }
```

- [ ] **Step 4: Write RecipeMacroResolver**

```kotlin
package com.macrotrack.app.data

import com.macrotrack.app.data.model.Recipe
import com.macrotrack.app.domain.MacroResolution
import com.macrotrack.app.domain.ScaledMacros
import com.macrotrack.app.domain.ServingScaler

/**
 * Resolves a recipe's per-serving macros by fetching each ingredient's
 * Food/CustomFood, scaling it (with the FoodServing fallback for Food
 * ingredients — CustomFood has no food_servings support in the schema, so
 * a CustomFood ingredient only resolves via a direct unit match), summing,
 * then dividing by the recipe's total servings. All the actual math is
 * MacroResolution's — this class is I/O only.
 */
class RecipeMacroResolver(
    private val recipeRepository: RecipeRepository,
    private val foodRepository: FoodRepository,
    private val customFoodRepository: CustomFoodRepository,
) {
    suspend fun resolvePerServingMacros(recipe: Recipe): ScaledMacros {
        val items = recipeRepository.getItems(recipe.id)
        val itemMacros = items.map { item ->
            val foodId = item.foodId
            val customFoodId = item.customFoodId
            when {
                foodId != null -> {
                    val food = foodRepository.getById(foodId)
                        ?: error("Recipe item ${item.id} references missing food $foodId")
                    val servings = foodRepository.getServings(food.id)
                    MacroResolution.resolveFoodMacros(food, servings, item.quantity, item.unit)
                }
                customFoodId != null -> {
                    val customFood = customFoodRepository.getById(customFoodId)
                        ?: error("Recipe item ${item.id} references missing custom food $customFoodId")
                    ServingScaler.scale(customFood, item.quantity, item.unit)
                }
                else -> error("Recipe item ${item.id} has neither food_id nor custom_food_id (violates the DB check constraint)")
            }
        }
        val total = MacroResolution.sumMacros(itemMacros)
        return MacroResolution.perServing(total, recipe.servings)
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/FoodRepository.kt \
        app/src/main/java/com/macrotrack/app/data/CustomFoodRepository.kt \
        app/src/main/java/com/macrotrack/app/data/RecipeRepository.kt \
        app/src/main/java/com/macrotrack/app/data/RecipeMacroResolver.kt
git commit -m "feat: add getById lookups and RecipeMacroResolver"
```

---

### Task 6: LogRepository

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/LogRepository.kt`

**Interfaces:**
- Consumes: `FoodLogEntry`/`NewFoodLogEntry`/`DailyTotals`/`EntryKind` (Task 2), `ScaledMacros`/`MacroResolution`/`ServingScaler` (Task 1/4), `FoodRepository`/`RecipeRepository`/`RecipeMacroResolver` (existing/Task 5), `Food`/`CustomFood` (existing)
- Produces: `LogRepository` interface with `listEntries`, `getDailyTotals`, `logFood`, `logCustomFood`, `logRecipeServings`, `logQuickAdd`, `deleteEntry`; `SupabaseLogRepository` implementation. Task 8's `AppContainer` wires this.

**No unit test in this task** — thin Postgrest wrapper, same reasoning as Task 5.

- [ ] **Step 1: Write the implementation**

```kotlin
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

    override suspend fun getDailyTotals(date: LocalDate): DailyTotals? {
        val userId = requireUserId()
        return client.postgrest.from("daily_nutrition_totals").select {
            filter {
                eq("user_id", userId)
                eq("log_date", date.toString())
            }
            limit(1)
        }.decodeSingleOrNull<DailyTotals>()
    }

    override suspend fun logFood(date: LocalDate, food: Food, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry {
        val servings = foodRepository.getServings(food.id)
        val macros = MacroResolution.resolveFoodMacros(food, servings, quantity, unit)
        return insertEntry(
            date = date, entryKind = EntryKind.FOOD, foodId = food.id, customFoodId = null, recipeId = null,
            quantity = quantity, unit = unit, macros = macros, displayName = food.name, meal = meal, notes = notes,
        )
    }

    override suspend fun logCustomFood(date: LocalDate, customFood: CustomFood, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry {
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/LogRepository.kt
git commit -m "feat: add LogRepository for snapshot-based daily food logging"
```

---

### Task 7: DayStatusRepository

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/DayStatusRepository.kt`

**Interfaces:**
- Consumes: `DailyLogStatus` (Task 3), `SupabaseClient`
- Produces: `DayStatusRepository` interface with `getStatus(date: LocalDate): DailyLogStatus?`, `setStatus(date: LocalDate, status: String, note: String? = null): DailyLogStatus`. Task 8's `AppContainer` wires this.

**No unit test in this task** — thin Postgrest wrapper.

- [ ] **Step 1: Write the implementation**

```kotlin
package com.macrotrack.app.data

import com.macrotrack.app.data.model.DailyLogStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import java.time.LocalDate

interface DayStatusRepository {
    suspend fun getStatus(date: LocalDate): DailyLogStatus?
    suspend fun setStatus(date: LocalDate, status: String, note: String? = null): DailyLogStatus
}

class SupabaseDayStatusRepository(private val client: SupabaseClient) : DayStatusRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("DayStatusRepository used before a user session exists.")
    }

    override suspend fun getStatus(date: LocalDate): DailyLogStatus? {
        val userId = requireUserId()
        return client.postgrest.from("daily_log_status").select {
            filter {
                eq("user_id", userId)
                eq("log_date", date.toString())
            }
            limit(1)
        }.decodeSingleOrNull<DailyLogStatus>()
    }

    override suspend fun setStatus(date: LocalDate, status: String, note: String?): DailyLogStatus {
        val userId = requireUserId()
        val payload = DailyLogStatus(userId = userId, logDate = date.toString(), status = status, note = note)
        return client.postgrest.from("daily_log_status").upsert(payload) { select() }.decodeSingle<DailyLogStatus>()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/DayStatusRepository.kt
git commit -m "feat: add DayStatusRepository for explicit day-state tracking"
```

---

### Task 8: AppContainer wiring

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`

**Interfaces:**
- Consumes: `LogRepository`/`SupabaseLogRepository` (Task 6), `DayStatusRepository`/`SupabaseDayStatusRepository` (Task 7), `RecipeMacroResolver` (Task 5), everything already wired
- Produces: `AppContainer.logRepository: LogRepository`, `AppContainer.dayStatusRepository: DayStatusRepository`

**No unit test** — pure dependency wiring, no branching logic, same reasoning as the food-repository slice's `AppContainer` task.

- [ ] **Step 1: Update the implementation**

```kotlin
package com.macrotrack.app.data

class AppContainer {
    private val client by lazy { SupabaseClientProvider.create() }

    val foodRepository: FoodRepository by lazy { SupabaseFoodRepository(client) }
    val customFoodRepository: CustomFoodRepository by lazy { SupabaseCustomFoodRepository(client) }
    val recipeRepository: RecipeRepository by lazy { SupabaseRecipeRepository(client) }
    val favoritesRepository: FavoritesRepository by lazy { SupabaseFavoritesRepository(client) }
    val recentFoodRepository: RecentFoodRepository by lazy { SupabaseRecentFoodRepository(client) }

    private val recipeMacroResolver by lazy { RecipeMacroResolver(recipeRepository, foodRepository, customFoodRepository) }
    val logRepository: LogRepository by lazy { SupabaseLogRepository(client, foodRepository, recipeRepository, recipeMacroResolver) }
    val dayStatusRepository: DayStatusRepository by lazy { SupabaseDayStatusRepository(client) }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/AppContainer.kt
git commit -m "feat: wire daily logger into AppContainer"
```

---

## Self-Review

**Spec coverage** (against CLAUDE.md step 3 — "Build the daily logger using snapshot nutrition values so historical logs do not change when a source food is edited" — plus the four numbered constraints in this plan's Global Constraints):

- Snapshot values, not re-derived from source rows → Task 2's `FoodLogEntry`/`NewFoodLogEntry` KDoc states it explicitly; Task 6's `LogRepository` writes `macros.calories`/etc. directly onto the insert payload and never re-joins `food_id`/`custom_food_id`/`recipe_id` to compute a total.
- Unlogged/partial days never read as zero → Task 2's `DailyTotals` KDoc + Task 6's `getDailyTotals` returns `null` (via `decodeSingleOrNull`) for a date with no rows, not a zeroed object.
- Explicit-zero-for-fasted primitive → Task 6's `logQuickAdd` accepts all-zero macros with no special-casing that would reject them; the "declare a fast" UI action (later slice) composes this with Task 7's `setStatus(date, DayStatus.FASTED)`.
- Food/custom food/recipe logging with exact scaling → Task 1 (Scalable), Task 4 (MacroResolution), Task 5 (RecipeMacroResolver), Task 6 (LogRepository) — all four entry kinds (`food`, `custom_food`, `recipe`, `quick_add`) have a `LogRepository` method.
- Day status independent of entries → Task 7's `DayStatusRepository` reads/writes `daily_log_status` directly, with no dependency on `food_log_entries`.

**Placeholder scan:** no TODO/TBD/"add appropriate handling" strings; every code block is complete, compilable Kotlin. The Postgrest DSL calls (`select`, `filter`, `eq`, `exact`, `order`, `limit`, `insert`, `update`, `upsert`, `set`, `decodeList`, `decodeSingle`, `decodeSingleOrNull`) all match the same verified `postgrest-kt`/`auth-kt` 3.7.0 API surface used and reviewed in the food-repository slice — `update`'s `set(column, value)` DSL was additionally checked against `PostgrestUpdate.kt`'s actual source for this plan.

**Type consistency:** `Scalable`'s 8 properties (Task 1) match exactly what `Food` and `CustomFood` already declare — verified against the real committed `FoodModels.kt`/`CustomFoodModels.kt` content, not assumed. `MacroResolution.resolveFoodMacros`'s signature (Task 4) matches its two call sites exactly (`RecipeMacroResolver` Task 5, `LogRepository.logFood` Task 6). `ScaledMacros` (already committed, unchanged) is the return type threaded through `ServingScaler` → `MacroResolution` → `RecipeMacroResolver` → `LogRepository` consistently.

**Known gap, tracked not hidden:** `NewFoodLogEntry` doesn't populate `nutrients`/`source_snapshot` jsonb columns (both default to `'{}'::jsonb`) — same class of gap as the food-repository slice's missing `nutrients` exposure on `Food`/`CustomFood`, and blocked on the same upstream fix. Record this in `docs/FOOD_REPOSITORY_GAPS.md` (or a renamed successor) once this plan lands, rather than silently leaving it undocumented.
