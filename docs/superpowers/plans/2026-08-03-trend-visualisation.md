# Trend Visualisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compute and persist a daily EWMA weight trend into `weight_trend_points`, bridging the sparse, instant-keyed rows `WeightRepository.listEntries` returns into the dense, gap-null-carrying per-day series `AdaptiveEngine.weightTrend` already expects — closing the exact gap recorded in `docs/WEIGHT_LOGGING_GAPS.md`.

**Architecture:** A pure-Kotlin domain function (`WeightTrendCalculator`, no Supabase/Android dependency, unit-tested directly) buckets raw weigh-ins into local calendar days, averages same-day duplicates, fills missing days with `null`, and hands the dense series to the already-built `AdaptiveEngine.weightTrend`. A new `TrendRepository` wraps that function with I/O: reading persisted trend points, and recomputing+upserting them from `WeightRepository`'s raw entries. `weight_trend_points`'s primary key is `(user_id, trend_date)`, so recomputation is a natural upsert-in-place, not an insert-then-never-touch history table.

**Tech Stack:** Kotlin, `java.time` (`Instant`/`LocalDate`/`ZoneId`/`OffsetDateTime`), kotlinx.serialization, `io.github.jan-tennert.supabase` postgrest-kt/auth-kt 3.7.0, JUnit4.

## Global Constraints

- `weight_trend_points` schema (`supabase/migrations/001_macro_foundation.sql:218-226`): `user_id uuid not null`, `trend_date date not null`, `trend_weight_kg numeric not null`, `method text not null default 'ewma_reference'`, `source_window_days integer not null default 14`, `created_at timestamptz not null default now()`, `primary key (user_id, trend_date)`. RLS policy `trend_owner_all`: `for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())`.
- `trend_weight_kg` is `NOT NULL` — a calendar day with no computed trend value (e.g. before the first ever weigh-in) must never be upserted with a sentinel; it must simply be skipped.
- The EWMA computation itself is `AdaptiveEngine.weightTrend(values: List<Double?>, alpha: Double = 0.20)` (`app/src/main/java/com/macrotrack/app/domain/AdaptiveEngine.kt`) — do not reimplement or approximate this algorithm. It expects one value per calendar day, ordered oldest-first, with explicit `null` for missing days, and carries the last non-null trend value forward across those nulls.
- Local-day bucketing uses `ZoneId.systemDefault()`, not UTC — `food_log_entries.log_date`/`daily_log_status.log_date` are already device-local `LocalDate` columns (fed from `LocalDate.toString()`), and trend bucketing must stay consistent with that day-keying, not drift to UTC.
- Multiple weigh-ins on the same local day are combined by averaging — the standard coaching convention — as one explicit, named, unit-tested function, never inlined arithmetic at a call site.
- `WeightEntry.measuredAt` (`app/src/main/java/com/macrotrack/app/data/model/WeightModels.kt`) is an opaque ISO-8601 `String`. Per `docs/WEIGHT_LOGGING_GAPS.md`, parse it with `OffsetDateTime.parse(value).toInstant()` — **never** `Instant.parse(value)`, which throws on the `+00:00`-offset form PostgREST can return on read (it only accepts the `Z`-suffixed form).
- Any persisted or displayed trend weight is rounded with `round1` (`app/src/main/java/com/macrotrack/app/domain/Rounding.kt`, `internal fun round1(value: Double): Double`), matching how `ExpenditureEstimate` rounds elsewhere. `round1` is `internal`, so it's visible module-wide (the whole `app` module) even though `TrendRepository` lives in a different package than `Rounding.kt`.
- Every repository's `requireUserId()` calls `client.auth.awaitInitialization()` before `client.auth.currentUserOrNull()`.
- `TrendRepository` reuses the verified Postgrest DSL: `client.postgrest.from(table).upsert(values: List<T>) { select() }.decodeList<T>()` — confirmed against the real postgrest-kt 3.7.0 sources (`PostgrestQueryBuilder.upsert(values: List<T>, request: UpsertRequestBuilder.() -> Unit)`, which serializes the list and calls the `JsonArray` overload).
- Out of scope: any UI/charting, `expenditure_estimates` persistence, `weekly_check_ins` wiring. Those are separate future slices.

---

### Task 1: WeightTrendCalculator

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/domain/WeightTrendCalculator.kt`
- Test: `app/src/test/java/com/macrotrack/app/domain/WeightTrendCalculatorTest.kt`

**Interfaces:**
- Consumes: `AdaptiveEngine.weightTrend(values: List<Double?>, alpha: Double = 0.20): List<Double?>` (already exists, `app/src/main/java/com/macrotrack/app/domain/AdaptiveEngine.kt`).
- Produces: `data class WeightSample(val measuredAt: Instant, val weightKg: Double)` and `object WeightTrendCalculator` with `fun dailyTrend(samples: List<WeightSample>, start: LocalDate, end: LocalDate, zoneId: ZoneId = ZoneId.systemDefault(), alpha: Double = 0.20): List<Pair<LocalDate, Double?>>`, both in package `com.macrotrack.app.domain`. Task 3 (`TrendRepository`) constructs `WeightSample` from parsed `WeightEntry` rows and calls `dailyTrend`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/macrotrack/app/domain/WeightTrendCalculatorTest.kt`:

```kotlin
package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WeightTrendCalculatorTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun emptyInputProducesAllNullDays() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 3)

        val result = WeightTrendCalculator.dailyTrend(emptyList(), start, end, zone)

        assertEquals(listOf(start, start.plusDays(1), end), result.map { it.first })
        assertEquals(listOf(null, null, null), result.map { it.second })
    }

    @Test
    fun singleEntrySetsTrendFromThatDayOnward() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 3)
        val samples = listOf(WeightSample(Instant.parse("2026-08-02T06:00:00Z"), 80.0))

        val result = WeightTrendCalculator.dailyTrend(samples, start, end, zone)

        assertNull(result[0].second)
        assertEquals(80.0, result[1].second!!, 0.0001)
        assertEquals(80.0, result[2].second!!, 0.0001)
    }

    @Test
    fun gapDayCarriesThePreviousTrendForward() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 4)
        val samples = listOf(
            WeightSample(Instant.parse("2026-08-01T06:00:00Z"), 80.0),
            // 2026-08-02 has no weigh-in -- a gap day
            WeightSample(Instant.parse("2026-08-03T06:00:00Z"), 81.0),
            WeightSample(Instant.parse("2026-08-04T06:00:00Z"), 79.0),
        )

        val result = WeightTrendCalculator.dailyTrend(samples, start, end, zone, alpha = 0.20)

        // AdaptiveEngine.weightTrend only recomputes on a non-null input day; on a
        // null (gap) day it returns whatever `previous` already holds unchanged --
        // i.e. the gap day's own trend equals the prior day's trend, not null.
        val expectedDay1 = 80.0
        val expectedDay2 = expectedDay1 // gap day carries day 1's trend forward
        val expectedDay3 = 0.20 * 81.0 + 0.80 * expectedDay2
        val expectedDay4 = 0.20 * 79.0 + 0.80 * expectedDay3

        assertEquals(expectedDay1, result[0].second!!, 0.0001)
        assertEquals(expectedDay2, result[1].second!!, 0.0001)
        assertEquals(expectedDay3, result[2].second!!, 0.0001)
        assertEquals(expectedDay4, result[3].second!!, 0.0001)
    }

    @Test
    fun sameDayDuplicatesAreAveragedBeforeFeedingTheTrend() {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 1)
        val samples = listOf(
            WeightSample(Instant.parse("2026-08-01T06:00:00Z"), 80.0),
            WeightSample(Instant.parse("2026-08-01T18:00:00Z"), 82.0),
        )

        val result = WeightTrendCalculator.dailyTrend(samples, start, end, zone)

        assertEquals(81.0, result[0].second!!, 0.0001)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.WeightTrendCalculatorTest"`
Expected: FAIL — `WeightSample`/`WeightTrendCalculator` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/macrotrack/app/domain/WeightTrendCalculator.kt`:

```kotlin
package com.macrotrack.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One raw weigh-in, already resolved to an absolute instant. */
data class WeightSample(val measuredAt: Instant, val weightKg: Double)

/**
 * Bridges sparse, instant-keyed weigh-ins into the dense, gap-null-carrying
 * per-local-day series `AdaptiveEngine.weightTrend` expects. Buckets by
 * `zoneId` (device-local, not UTC, to match `log_date`'s day-keying
 * elsewhere in this app), averages same-day duplicates, and leaves a day
 * with no weigh-in as `null` so the EWMA gap-carry-forward behavior
 * actually engages.
 */
object WeightTrendCalculator {

    fun dailyTrend(
        samples: List<WeightSample>,
        start: LocalDate,
        end: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        alpha: Double = 0.20,
    ): List<Pair<LocalDate, Double?>> {
        require(!end.isBefore(start)) { "end must not be before start, got start=$start end=$end" }
        val averagedByDay = samples
            .groupBy { it.measuredAt.atZone(zoneId).toLocalDate() }
            .mapValues { (_, dayEntries) -> dayEntries.map { it.weightKg }.average() }

        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val denseSeries = days.map { averagedByDay[it] }
        val trend = AdaptiveEngine.weightTrend(denseSeries, alpha)
        return days.zip(trend)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.WeightTrendCalculatorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/domain/WeightTrendCalculator.kt app/src/test/java/com/macrotrack/app/domain/WeightTrendCalculatorTest.kt
git commit -m "feat: add WeightTrendCalculator bridging sparse weigh-ins to AdaptiveEngine's dense trend series"
```

---

### Task 2: WeightTrendModels

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/model/WeightTrendModels.kt`
- Test: `app/src/test/java/com/macrotrack/app/data/model/WeightTrendModelsTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `TrendPoint` (decode model: `userId`, `trendDate: String`, `trendWeightKg: Double`, `method: String`, `sourceWindowDays: Int`, `createdAt: String`) and `NewTrendPoint` (upsert payload: `userId`, `trendDate: String`, `trendWeightKg: Double`, `method: String = "ewma_reference"`, `sourceWindowDays: Int = 14` — no `createdAt`, server-generated). Both `@Serializable` data classes in package `com.macrotrack.app.data.model`. Task 3 constructs `NewTrendPoint` and decodes `TrendPoint`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/macrotrack/app/data/model/WeightTrendModelsTest.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightTrendModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesATrendPointRow() {
        val row = """
            {
              "user_id": "user-1",
              "trend_date": "2026-08-03",
              "trend_weight_kg": 81.4,
              "method": "ewma_reference",
              "source_window_days": 14,
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val point = json.decodeFromString(TrendPoint.serializer(), row)

        assertEquals("user-1", point.userId)
        assertEquals("2026-08-03", point.trendDate)
        assertEquals(81.4, point.trendWeightKg, 0.001)
        assertEquals("ewma_reference", point.method)
        assertEquals(14, point.sourceWindowDays)
        assertEquals("2026-08-03T06:30:05Z", point.createdAt)
    }

    @Test
    fun encodesANewTrendPointPayloadWithAnExplicitNonDefaultMethod() {
        val payload = NewTrendPoint(
            userId = "user-1",
            trendDate = "2026-08-03",
            trendWeightKg = 81.4,
            method = "manual_override",
        )

        val encoded = json.encodeToString(NewTrendPoint.serializer(), payload)

        assertEquals(true, encoded.contains("\"trend_weight_kg\":81.4"))
        assertEquals(true, encoded.contains("\"method\":\"manual_override\""))
        assertEquals(false, encoded.contains("\"created_at\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.WeightTrendModelsTest"`
Expected: FAIL — `TrendPoint`/`NewTrendPoint` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/macrotrack/app/data/model/WeightTrendModels.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `public.weight_trend_points`. Primary key is `(user_id, trend_date)`. */
@Serializable
data class TrendPoint(
    @SerialName("user_id") val userId: String,
    @SerialName("trend_date") val trendDate: String,
    @SerialName("trend_weight_kg") val trendWeightKg: Double,
    val method: String,
    @SerialName("source_window_days") val sourceWindowDays: Int,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Upsert payload for a recomputed trend point. `user_id` is filled in by
 * the repository from the current session, never trusted from the caller.
 * `created_at` is server-generated and omitted here.
 */
@Serializable
data class NewTrendPoint(
    @SerialName("user_id") val userId: String,
    @SerialName("trend_date") val trendDate: String,
    @SerialName("trend_weight_kg") val trendWeightKg: Double,
    val method: String = "ewma_reference",
    @SerialName("source_window_days") val sourceWindowDays: Int = 14,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.WeightTrendModelsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/model/WeightTrendModels.kt app/src/test/java/com/macrotrack/app/data/model/WeightTrendModelsTest.kt
git commit -m "feat: add TrendPoint/NewTrendPoint models"
```

---

### Task 3: TrendRepository

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/TrendRepository.kt`

**Interfaces:**
- Consumes: `TrendPoint`/`NewTrendPoint` (Task 2, `com.macrotrack.app.data.model`); `WeightSample`/`WeightTrendCalculator.dailyTrend` (Task 1, `com.macrotrack.app.domain`); `WeightRepository.listEntries(since: Instant): List<WeightEntry>` (already exists, `app/src/main/java/com/macrotrack/app/data/WeightRepository.kt`); `round1` (already exists, `com.macrotrack.app.domain.Rounding.kt`, `internal`, module-visible).
- Produces: `TrendRepository` interface with `suspend fun listTrendPoints(since: Instant): List<TrendPoint>` and `suspend fun recomputeTrend(since: Instant): List<TrendPoint>`; and `SupabaseTrendRepository(client: SupabaseClient, weightRepository: WeightRepository) : TrendRepository`. Task 4 (`AppContainer`) constructs `SupabaseTrendRepository(client, weightRepository)`.

This is a thin Postgrest I/O wrapper (plus a call into the pure Task 1 function) — matching `LogRepository`/`WeightRepository`, it has no dedicated unit test in this plan (no live Supabase project exists in this sandbox; verified instead by static review against the real postgrest-kt 3.7.0 sources, same as prior slices).

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/macrotrack/app/data/TrendRepository.kt`:

```kotlin
package com.macrotrack.app.data

import com.macrotrack.app.data.model.NewTrendPoint
import com.macrotrack.app.data.model.TrendPoint
import com.macrotrack.app.domain.WeightSample
import com.macrotrack.app.domain.WeightTrendCalculator
import com.macrotrack.app.domain.round1
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

interface TrendRepository {
    suspend fun listTrendPoints(since: Instant): List<TrendPoint>
    suspend fun recomputeTrend(since: Instant): List<TrendPoint>
}

class SupabaseTrendRepository(
    private val client: SupabaseClient,
    private val weightRepository: WeightRepository,
) : TrendRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("TrendRepository used before a user session exists.")
    }

    override suspend fun listTrendPoints(since: Instant): List<TrendPoint> {
        val userId = requireUserId()
        val sinceDate = LocalDate.ofInstant(since, ZoneId.systemDefault())
        return client.postgrest.from("weight_trend_points").select {
            filter {
                eq("user_id", userId)
                gte("trend_date", sinceDate.toString())
            }
            order("trend_date", Order.ASCENDING)
        }.decodeList<TrendPoint>()
    }

    override suspend fun recomputeTrend(since: Instant): List<TrendPoint> {
        val userId = requireUserId()
        val zoneId = ZoneId.systemDefault()
        val entries = weightRepository.listEntries(since)
        val samples = entries.map { WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg) }
        val start = LocalDate.ofInstant(since, zoneId)
        val end = LocalDate.now(zoneId)
        val series = WeightTrendCalculator.dailyTrend(samples, start, end, zoneId)

        val payload = series.mapNotNull { (day, trendWeightKg) ->
            trendWeightKg?.let {
                NewTrendPoint(userId = userId, trendDate = day.toString(), trendWeightKg = round1(it))
            }
        }
        if (payload.isEmpty()) return emptyList()
        return client.postgrest.from("weight_trend_points").upsert(payload) { select() }.decodeList<TrendPoint>()
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compiles cleanly (or, if the Android SDK is unavailable in this sandbox, say so explicitly and rely on the manual API-surface review below instead of claiming this ran).

Manual verification performed in place of a live compile (record in the task report): `upsert(payload) { select() }` where `payload: List<NewTrendPoint>` matches `PostgrestQueryBuilder.upsert(values: List<T>, request: UpsertRequestBuilder.() -> Unit = {})` from the real postgrest-kt 3.7.0 sources (`io/github/jan/supabase/postgrest/query/PostgrestQueryBuilder.kt`), which serializes the list with `postgrest.serializer.encodeToJsonElement(values)` and delegates to the `JsonArray` overload — an established pattern, `DayStatusRepository.setStatus` already uses the single-value `upsert(payload) { select() }` overload of the same function family. `round1` (from `com.macrotrack.app.domain.Rounding.kt`) is declared `internal`, which in Kotlin is module-visible, not package-visible — `TrendRepository` (package `com.macrotrack.app.data`) can call it from within the same `app` Gradle module without a visibility error.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/TrendRepository.kt
git commit -m "feat: add TrendRepository computing and upserting weight_trend_points from WeightRepository entries"
```

---

### Task 4: AppContainer wiring

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`

**Interfaces:**
- Consumes: `TrendRepository`/`SupabaseTrendRepository` from Task 3.
- Produces: `AppContainer.trendRepository: TrendRepository`, available to any future caller (e.g. a trend-visualisation ViewModel) the same way `AppContainer.weightRepository` already is.

- [ ] **Step 1: Add the wiring**

In `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`, add one line after `weightRepository`, giving `SupabaseTrendRepository` the already-constructed `weightRepository` as its second constructor argument:

```kotlin
    val trendRepository: TrendRepository by lazy { SupabaseTrendRepository(client, weightRepository) }
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
    val trendRepository: TrendRepository by lazy { SupabaseTrendRepository(client, weightRepository) }
}
```

- [ ] **Step 2: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all existing tests plus `WeightTrendCalculatorTest`/`WeightTrendModelsTest` green.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/AppContainer.kt
git commit -m "feat: wire TrendRepository into AppContainer"
```

---

## Self-Review

**1. Spec coverage:** Domain bucketing/averaging/gap-filling function with the four required test cases (single entry, gap-day carry-forward, same-day averaging, empty input) — Task 1 ✅. `TrendPoint`/`NewTrendPoint` models — Task 2 ✅. `TrendRepository` with `listTrendPoints`/`recomputeTrend`, upserting via the verified `upsert(List<T>)` DSL, skipping null days rather than writing a sentinel into the `NOT NULL` `trend_weight_kg` column — Task 3 ✅. `AppContainer` wiring, `TrendRepository` depending on `WeightRepository` the same way `LogRepository` depends on `FoodRepository`/`RecipeRepository`/`RecipeMacroResolver` — Task 4 ✅. All three documented product decisions from `docs/WEIGHT_LOGGING_GAPS.md` are made explicitly rather than left implicit: local-zone bucketing (`ZoneId.systemDefault()`, Global Constraints + Task 1), same-day averaging (a named, tested function, Task 1), gap-day nulling (Task 1's `averagedByDay[it]` returning `null` for absent days) ✅. `OffsetDateTime.parse` (not `Instant.parse`) used for `measuredAt` in Task 3 ✅. UI/charting/`expenditure_estimates`/`weekly_check_ins` explicitly excluded ✅.

**2. Placeholder scan:** No TBD/TODO; all code blocks are complete; no "similar to Task N" references — none found.

**3. Type consistency:** `WeightSample(measuredAt: Instant, weightKg: Double)` (Task 1) matches its construction in Task 3 (`WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg)`, `it: WeightEntry` whose `weightKg: Double` and `measuredAt: String` are the pre-existing types from the weight-logging slice). `WeightTrendCalculator.dailyTrend(...): List<Pair<LocalDate, Double?>>` matches Task 3's destructuring (`series.mapNotNull { (day, trendWeightKg) -> ... }`). `TrendPoint.trendWeightKg: Double`/`NewTrendPoint.trendWeightKg: Double` (Task 2) match `round1(it): Double` (Task 3's payload construction) and the schema's `numeric not null`. `AppContainer.trendRepository`'s declared type `TrendRepository` and constructor `SupabaseTrendRepository(client, weightRepository)` match Task 3's produced interface/impl names and constructor signature exactly (`client: SupabaseClient, weightRepository: WeightRepository`).
