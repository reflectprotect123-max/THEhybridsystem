# Weight Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `weight_entries` CRUD data layer for MacroTrack — models, a Supabase-backed repository, and DI wiring — so a future trend-visualisation slice has real weight data to feed into the already-built `AdaptiveEngine.weightTrend`.

**Architecture:** Same repository-per-table pattern already used for `LogRepository`/`DayStatusRepository`: a kotlinx.serialization decode model plus a separate insert-payload model, an interface + `Supabase*Repository` implementation pair in one file, wired into `AppContainer` via `by lazy`. `weight_entries` has no `deleted_at` column (confirmed against `supabase/migrations/001_macro_foundation.sql:207-226`), so deletion is a real hard `DELETE`, not the soft-delete `UPDATE` pattern `LogRepository.deleteEntry` uses.

**Tech Stack:** Kotlin, kotlinx.serialization, `io.github.jan-tennert.supabase` postgrest-kt/auth-kt 3.7.0 (Postgrest DSL — `select`/`filter`/`eq`/`gte`/`order`/`limit`/`insert`/`delete`, `decodeList`/`decodeSingle`; Auth — `auth.awaitInitialization()`/`auth.currentUserOrNull()`), JUnit4 for model serialization tests.

## Global Constraints

- `weight_entries` schema (`supabase/migrations/001_macro_foundation.sql:207-226`): `id uuid primary key default gen_random_uuid()`, `user_id uuid not null references auth.users(id) on delete cascade`, `measured_at timestamptz not null`, `weight_kg numeric not null check (weight_kg between 20 and 500)`, `source text not null default 'manual'`, `note text` (nullable), `created_at timestamptz not null default now()`. Index on `(user_id, measured_at)`. RLS policy `weight_owner_all`: `for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())`.
- `weight_entries` has **no `deleted_at` column** — `deleteEntry` must issue a real Postgrest `delete { filter { ... } }`, never an `update` that sets a soft-delete marker.
- Any caller-suppliable numeric value with a DB check constraint gets a client-side `require()` guard before any I/O. `weight_kg`'s constraint is `between 20 and 500`, so `logWeight` must `require(weightKg in 20.0..500.0)` before calling Postgrest.
- Every repository's `requireUserId()` must call `client.auth.awaitInitialization()` before `client.auth.currentUserOrNull()` — a Critical finding from an earlier slice's final review, applied from the start this time.
- `deleteEntry` filters by both `id` and `user_id` (defense-in-depth alongside RLS), matching `LogRepository.deleteEntry`'s pattern.
- Out of scope for this plan: `weight_trend_points`, any UI, any charting. Those belong to the future trend-visualisation slice, which will consume this repository's `listEntries` plus the existing `AdaptiveEngine.weightTrend` (`app/src/main/java/com/macrotrack/app/domain/AdaptiveEngine.kt`).
- Model field naming/style: `override`/plain `val` properties in `camelCase` with `@SerialName("snake_case")` where the DB column differs, matching `CustomFood`/`FoodLogEntry` in `app/src/main/java/com/macrotrack/app/data/model/`.

---

### Task 1: WeightModels

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/model/WeightModels.kt`
- Test: `app/src/test/java/com/macrotrack/app/data/model/WeightModelsTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `WeightEntry` (decode model: `id`, `userId`, `measuredAt: String`, `weightKg: Double`, `source: String`, `note: String?`, `createdAt: String`) and `NewWeightEntry` (insert payload: `userId`, `measuredAt: String`, `weightKg: Double`, `source: String = "manual"`, `note: String? = null` — no `id`/`createdAt`, matching the pattern where server-generated columns are omitted from the `New*` payload). Both are `@Serializable` data classes in package `com.macrotrack.app.data.model`. Task 2 constructs `NewWeightEntry` and decodes `WeightEntry`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/macrotrack/app/data/model/WeightModelsTest.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAWeightEntryRow() {
        val row = """
            {
              "id": "weight-1",
              "user_id": "user-1",
              "measured_at": "2026-08-03T06:30:00Z",
              "weight_kg": 82.4,
              "source": "manual",
              "note": "morning, fasted",
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val entry = json.decodeFromString(WeightEntry.serializer(), row)

        assertEquals("weight-1", entry.id)
        assertEquals("user-1", entry.userId)
        assertEquals("2026-08-03T06:30:00Z", entry.measuredAt)
        assertEquals(82.4, entry.weightKg, 0.001)
        assertEquals("manual", entry.source)
        assertEquals("morning, fasted", entry.note)
        assertEquals("2026-08-03T06:30:05Z", entry.createdAt)
    }

    @Test
    fun decodesAWeightEntryRowWithNoNote() {
        val row = """
            {
              "id": "weight-2",
              "user_id": "user-1",
              "measured_at": "2026-08-04T06:30:00Z",
              "weight_kg": 82.1,
              "source": "manual",
              "note": null,
              "created_at": "2026-08-04T06:30:05Z"
            }
        """.trimIndent()

        val entry = json.decodeFromString(WeightEntry.serializer(), row)

        assertNull(entry.note)
    }

    @Test
    fun encodesANewWeightEntryPayloadWithDefaultSource() {
        val payload = NewWeightEntry(
            userId = "user-1",
            measuredAt = "2026-08-03T06:30:00Z",
            weightKg = 82.4,
        )

        val encoded = json.encodeToString(NewWeightEntry.serializer(), payload)

        assertEquals(true, encoded.contains("\"weight_kg\":82.4"))
        assertEquals(true, encoded.contains("\"source\":\"manual\""))
        assertEquals(false, encoded.contains("\"id\""))
        assertEquals(false, encoded.contains("\"created_at\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.WeightModelsTest"`
Expected: FAIL — `WeightEntry`/`NewWeightEntry` are unresolved references (the file doesn't exist yet).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/macrotrack/app/data/model/WeightModels.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.weight_entries`. */
@Serializable
data class WeightEntry(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
    val source: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Insert payload for a new weight entry. `user_id` is filled in by the
 * repository from the current session, never trusted from the caller.
 * `id`/`created_at` are server-generated and omitted here.
 */
@Serializable
data class NewWeightEntry(
    @SerialName("user_id") val userId: String,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
    val source: String = "manual",
    val note: String? = null,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.WeightModelsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/model/WeightModels.kt app/src/test/java/com/macrotrack/app/data/model/WeightModelsTest.kt
git commit -m "feat: add WeightEntry/NewWeightEntry models"
```

---

### Task 2: WeightRepository

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/WeightRepository.kt`

**Interfaces:**
- Consumes: `WeightEntry`/`NewWeightEntry` from Task 1 (`com.macrotrack.app.data.model`).
- Produces: `WeightRepository` interface with `suspend fun listEntries(since: java.time.Instant): List<WeightEntry>`, `suspend fun logWeight(measuredAt: java.time.Instant, weightKg: Double, source: String = "manual", note: String? = null): WeightEntry`, `suspend fun deleteEntry(entryId: String)`; and `SupabaseWeightRepository(client: SupabaseClient) : WeightRepository`. Task 3 (`AppContainer`) constructs `SupabaseWeightRepository(client)`.

This is a thin Postgrest I/O wrapper — matching `LogRepository`/`DayStatusRepository`/`CustomFoodRepository`, it has no dedicated unit test in this plan (no live Supabase project exists in this sandbox to test against; verified instead by static review against the real postgrest-kt 3.7.0 sources, same as prior slices).

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/macrotrack/app/data/WeightRepository.kt`:

```kotlin
package com.macrotrack.app.data

import com.macrotrack.app.data.model.NewWeightEntry
import com.macrotrack.app.data.model.WeightEntry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant

interface WeightRepository {
    suspend fun listEntries(since: Instant): List<WeightEntry>
    suspend fun logWeight(measuredAt: Instant, weightKg: Double, source: String = "manual", note: String? = null): WeightEntry
    suspend fun deleteEntry(entryId: String)
}

class SupabaseWeightRepository(private val client: SupabaseClient) : WeightRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("WeightRepository used before a user session exists.")
    }

    override suspend fun listEntries(since: Instant): List<WeightEntry> {
        val userId = requireUserId()
        return client.postgrest.from("weight_entries").select {
            filter {
                eq("user_id", userId)
                gte("measured_at", since.toString())
            }
            order("measured_at", Order.ASCENDING)
        }.decodeList<WeightEntry>()
    }

    override suspend fun logWeight(measuredAt: Instant, weightKg: Double, source: String, note: String?): WeightEntry {
        require(weightKg in 20.0..500.0) { "weightKg must be between 20 and 500, got $weightKg" }
        val userId = requireUserId()
        val payload = NewWeightEntry(
            userId = userId,
            measuredAt = measuredAt.toString(),
            weightKg = weightKg,
            source = source,
            note = note,
        )
        return client.postgrest.from("weight_entries").insert(payload) { select() }.decodeSingle<WeightEntry>()
    }

    override suspend fun deleteEntry(entryId: String) {
        val userId = requireUserId()
        client.postgrest.from("weight_entries").delete {
            filter {
                eq("id", entryId)
                eq("user_id", userId)
            }
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compiles cleanly (or, if the Android SDK is unavailable in this sandbox, say so explicitly and rely on the manual API-surface review below instead of claiming this ran).

Manual verification performed in place of a live compile (record in the task report): `delete { filter { ... } }` matches `PostgrestQueryBuilder.delete(request: PostgrestRequestBuilder.() -> Unit = {})` from the real postgrest-kt 3.7.0 sources (`io/github/jan/supabase/postgrest/query/PostgrestQueryBuilder.kt`), which builds a `DeleteRequestBuilder` (a `PostgrestRequestBuilder`, so the same `filter { eq(...) }` DSL used by `select`/`update` applies unchanged) and issues an `HttpMethod.Delete` request — this is a genuine hard delete, not a filtered update.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/WeightRepository.kt
git commit -m "feat: add WeightRepository with list/log/delete against weight_entries"
```

---

### Task 3: AppContainer wiring

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`

**Interfaces:**
- Consumes: `WeightRepository`/`SupabaseWeightRepository` from Task 2.
- Produces: `AppContainer.weightRepository: WeightRepository`, available to any future caller (e.g. the trend-visualisation slice's ViewModel) the same way `AppContainer.logRepository` already is.

- [ ] **Step 1: Add the wiring**

In `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`, add one line alongside the existing repository properties:

```kotlin
    val weightRepository: WeightRepository by lazy { SupabaseWeightRepository(client) }
```

Resulting file:

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
    val weightRepository: WeightRepository by lazy { SupabaseWeightRepository(client) }
}
```

- [ ] **Step 2: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all existing tests plus `WeightModelsTest` green.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/AppContainer.kt
git commit -m "feat: wire WeightRepository into AppContainer"
```

---

## Self-Review

**1. Spec coverage:** `WeightEntry`/`NewWeightEntry` models (Task 1) ✅. `WeightRepository` with `listEntries` ordered oldest-first from an instant, `logWeight` with the 20-500kg guard, hard-delete `deleteEntry` scoped by `id` and `user_id` (Task 2) ✅. `AppContainer` wiring (Task 3) ✅. Both prior-review lessons (`awaitInitialization()` before `currentUserOrNull()`; client-side `require()` matching the DB check constraint) are in the Task 2 code from the start, not deferred to a fix wave ✅. `weight_trend_points`/UI/charting explicitly excluded per Global Constraints ✅.

**2. Placeholder scan:** No TBD/TODO; all code blocks are complete; no "similar to Task N" references — none found.

**3. Type consistency:** `WeightEntry.weightKg: Double` (Task 1) matches `WeightRepository.logWeight(weightKg: Double, ...)`'s parameter (Task 2) and the `20.0..500.0` guard's operand type. `WeightEntry.id: String` matches `deleteEntry(entryId: String)`. `NewWeightEntry.measuredAt: String` is constructed from `Instant.toString()` in `logWeight`, matching the ISO-8601 `timestamptz` string format `LogRepository.deleteEntry` already uses (`Instant.now().toString()`) for the same column type elsewhere in this codebase. `AppContainer.weightRepository`'s declared type `WeightRepository` and constructor `SupabaseWeightRepository(client)` match Task 2's produced interface/impl names exactly.
