# Expenditure State Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist `AdaptiveEngine.estimateExpenditure`'s output into `expenditure_estimates`, closing `docs/ADAPTIVE_ENGINE_GAPS.md`'s "nullable engine fields vs. NOT NULL schema columns" gap and giving the engine real logged data to run on for the first time.

**Architecture:** Two existing repositories (`DayStatusRepository`, `LogRepository`) gain a bulk range-read method each. A new pure-Kotlin `ExpenditureRecordAssembler` combines those bulk reads plus weight data into the dense `List<DailyRecord>` the engine expects. A new `ExpenditureRepository` wraps the whole pipeline: fetch full history, assemble records, call `AdaptiveEngine.estimateExpenditure`, persist the result when it has real numbers to store, and always return the computed estimate regardless of whether a row was written.

**Tech Stack:** Kotlin, `java.time` (`Instant`/`LocalDate`/`ZoneId`/`OffsetDateTime`), kotlinx.serialization (including `kotlinx.serialization.json.JsonObject`/`buildJsonObject`), `io.github.jan-tennert.supabase` postgrest-kt/auth-kt 3.7.0, JUnit4.

## Global Constraints

- `expenditure_estimates` schema (`supabase/migrations/001_macro_foundation.sql:261-277`): `id uuid primary key default gen_random_uuid()`, `user_id uuid not null`, `window_start date not null`, `window_end date not null`, `estimate_kcal numeric not null`, `previous_estimate_kcal numeric`, `raw_estimate_kcal numeric`, `trend_slope_kg_per_week numeric`, `nutrition_days integer not null default 0`, `weight_days integer not null default 0`, `confidence text not null check (confidence in ('holding','low','medium','high'))`, `state text not null check (state in ('holding','updating'))`, `method text not null default 'intake_minus_trend_energy'`, `inputs jsonb not null default '{}'::jsonb`, `created_at timestamptz not null default now()`. RLS policy `expenditure_owner_all`: `for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())`.
- `id` is its own primary key here, **not** a composite of `(user_id, some_date)` — this table is an append-only history log of recompute events, unlike `weight_trend_points`. Persistence in this slice is always a plain `insert()`, never `upsert()`.
- **Only persist a row when `estimate.estimateKcal != null AND estimate.windowStart != null AND estimate.windowEnd != null`.** `AdaptiveEngine.estimateExpenditure` (`app/src/main/java/com/macrotrack/app/domain/AdaptiveEngine.kt`) only leaves all three null together when the input `records` list is completely empty; `estimateKcal` can also be null on a holding path with no `previousEstimateKcal` to carry forward. Every other real path — including ordinary holding once a previous estimate exists — has all three fields defined and gets persisted normally. This mirrors this repo's existing precedent (`daily_nutrition_totals`, `weight_trend_points`) that "no row" is how "nothing to report" is represented, never a fabricated sentinel. A caller finding no persisted row means "never enough information to say anything, ever" — CLAUDE.md's "missing-data holding is a valid state and must be visible" is satisfied because ordinary holding-with-a-carried-number IS persisted; only the true nothing-at-all case is unpersisted.
- **`inputs jsonb` stores `{"explanation": "<ExpenditureEstimate.explanation>"}`.** The schema has no `explanation` text column at all, but the engine always computes one. `inputs` otherwise has no defined contract anywhere in this repo's source-of-truth docs (`docs/ADAPTIVE_ENGINE_CONTRACT.md` and `adaptive_engine.py` never mention it) — this plan defines its contract as exactly this one key. Document this in `docs/EXPENDITURE_STATE_GAPS.md` as this repo's explicit product choice, not an inherited spec.
- `NewExpenditureEstimate.inputs` must be a **required** constructor parameter (no default value) so kotlinx.serialization's `encodeDefaults = false` can never accidentally omit it from the wire payload — this exact class of bug (a default-valued field silently omitted, then a test wrongly asserting its presence) has bitten two previous slices in this session.
- Do not reimplement or approximate `AdaptiveEngine.estimateExpenditure` — call it directly.
- `estimateExpenditure`'s internal `weightTrend` call seeds its EWMA from the **full** `records` list (sorted by day), not a caller-bounded recent window. Feeding it anything less than full history produces a non-deterministic result depending on an arbitrary boundary — exactly the bug the trend-visualisation slice hit and fixed (`docs/TREND_VISUALISATION_GAPS.md`). This slice fetches full history from the start, not as a later fix.
- Any `Instant`-to-`LocalDate` conversion uses `instant.atZone(zoneId).toLocalDate()`, **never** `LocalDate.ofInstant(instant, zoneId)` — the latter is a Java 9 `java.time` addition; this module's `minSdk` is 26 with no core-library desugaring, and using it caused a real runtime-crashing Critical bug in the trend-visualisation slice.
- Every repository's `requireUserId()` calls `client.auth.awaitInitialization()` before `client.auth.currentUserOrNull()`.
- A `filter { }` block must never apply two direct conditions to the *same* column (e.g. two `gte`/`lte` calls on one column name) — postgrest-kt's top-level request params are keyed by column and folded to their first value only, silently dropping the second condition (a verified Critical bug from the trend-visualisation slice's final review). This plan's bulk reads only ever need a single one-sided `gte` bound per column, so this should not arise — but if any implementer finds themselves reaching for a second bound on the same column, stop and use `and { }` grouping instead (see `TrendRepository.kt`'s `recomputeTrend` for the verified pattern), never two direct calls.
- Reuse the verified Postgrest DSL surface (`select`/`filter`/`eq`/`gte`/`order`/`insert`/`limit`, `decodeList`/`decodeSingleOrNull`) from prior slices — don't re-derive it.
- Out of scope: any UI, the weekly check-in flow, `macro_programs`/`user_nutrient_targets` tables. Those are separate future slices.

---

### Task 1: DayStatusRepository.listStatuses

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/DayStatusRepository.kt`

**Interfaces:**
- Consumes: `DailyLogStatus` (already exists, `app/src/main/java/com/macrotrack/app/data/model/DayStatusModels.kt`).
- Produces: `DayStatusRepository.listStatuses(since: LocalDate): List<DailyLogStatus>`, added to both the interface and `SupabaseDayStatusRepository`. Task 5 (`ExpenditureRepository`) calls this.

This is a thin Postgrest I/O addition — no dedicated unit test (matching this repository's existing `getStatus`/`setStatus`, neither of which has one; no live Supabase project exists in this sandbox).

- [ ] **Step 1: Add the method**

In `app/src/main/java/com/macrotrack/app/data/DayStatusRepository.kt`, add `import java.time.LocalDate` is already present; add `listStatuses` to the interface:

```kotlin
interface DayStatusRepository {
    suspend fun getStatus(date: LocalDate): DailyLogStatus?
    suspend fun setStatus(date: LocalDate, status: String, note: String? = null): DailyLogStatus
    suspend fun listStatuses(since: LocalDate): List<DailyLogStatus>
}
```

And to `SupabaseDayStatusRepository`, following `getStatus`'s existing pattern exactly:

```kotlin
    override suspend fun listStatuses(since: LocalDate): List<DailyLogStatus> {
        val userId = requireUserId()
        return client.postgrest.from("daily_log_status").select {
            filter {
                eq("user_id", userId)
                gte("log_date", since.toString())
            }
            order("log_date", Order.ASCENDING)
        }.decodeList<DailyLogStatus>()
    }
```

This requires adding `import io.github.jan.supabase.postgrest.query.Order` to the file's imports (not already present — `getStatus`/`setStatus` don't order results).

Resulting file:

```kotlin
package com.macrotrack.app.data

import com.macrotrack.app.data.model.DailyLogStatus
import com.macrotrack.app.data.model.DayStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

interface DayStatusRepository {
    suspend fun getStatus(date: LocalDate): DailyLogStatus?
    suspend fun setStatus(date: LocalDate, status: String, note: String? = null): DailyLogStatus
    suspend fun listStatuses(since: LocalDate): List<DailyLogStatus>
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
        require(status in VALID_STATUSES) {
            "status must be one of $VALID_STATUSES, got '$status'"
        }
        val userId = requireUserId()
        val payload = DailyLogStatus(userId = userId, logDate = date.toString(), status = status, note = note)
        return client.postgrest.from("daily_log_status").upsert(payload) { select() }.decodeSingle<DailyLogStatus>()
    }

    override suspend fun listStatuses(since: LocalDate): List<DailyLogStatus> {
        val userId = requireUserId()
        return client.postgrest.from("daily_log_status").select {
            filter {
                eq("user_id", userId)
                gte("log_date", since.toString())
            }
            order("log_date", Order.ASCENDING)
        }.decodeList<DailyLogStatus>()
    }

    companion object {
        private val VALID_STATUSES = setOf(DayStatus.COMPLETE, DayStatus.PARTIAL, DayStatus.FASTED, DayStatus.UNLOGGED)
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compiles cleanly (or, if the Android SDK is unavailable in this sandbox, say so explicitly and rely on manual review — a known, standing limitation in this repo).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/DayStatusRepository.kt
git commit -m "feat: add DayStatusRepository.listStatuses bulk range read"
```

---

### Task 2: LogRepository.listDailyTotals

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/LogRepository.kt`

**Interfaces:**
- Consumes: `DailyTotals` (already exists, `app/src/main/java/com/macrotrack/app/data/model/LogEntryModels.kt`).
- Produces: `LogRepository.listDailyTotals(since: LocalDate): List<DailyTotals>`, added to both the interface and `SupabaseLogRepository`. Task 5 (`ExpenditureRepository`) calls this.

No dedicated unit test — same reasoning as Task 1.

- [ ] **Step 1: Add the method**

In `app/src/main/java/com/macrotrack/app/data/LogRepository.kt`, add to the interface (after `getDailyTotals`):

```kotlin
    suspend fun listDailyTotals(since: LocalDate): List<DailyTotals>
```

And to `SupabaseLogRepository`, reusing the exact same false-zero-row guard `getDailyTotals` already applies (a day with all entries soft-deleted can emit a `entry_count=0` row from the view, which must never read as a confident zero):

```kotlin
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
```

`Order` is already imported in this file (used by `listEntries`).

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compiles cleanly (or say so explicitly if the sandbox can't run it, per the standing limitation).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/LogRepository.kt
git commit -m "feat: add LogRepository.listDailyTotals bulk range read"
```

---

### Task 3: ExpenditureRecordAssembler

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/domain/ExpenditureRecordAssembler.kt`
- Test: `app/src/test/java/com/macrotrack/app/domain/ExpenditureRecordAssemblerTest.kt`

**Interfaces:**
- Consumes: `DailyLogStatus`/`DailyTotals` (`com.macrotrack.app.data.model`, already exist), `DayStatus` (already exists), `DailyRecord` (already exists, `app/src/main/java/com/macrotrack/app/domain/AdaptiveEngineModels.kt`).
- Produces: `object ExpenditureRecordAssembler` with `fun assemble(statuses: List<DailyLogStatus>, totals: List<DailyTotals>, weightByDay: Map<LocalDate, Double>, start: LocalDate, end: LocalDate): List<DailyRecord>` in package `com.macrotrack.app.domain`. Task 5 (`ExpenditureRepository`) calls this, passing the weight-by-day map from `WeightTrendCalculator.averageByLocalDay` (already exists).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/macrotrack/app/domain/ExpenditureRecordAssemblerTest.kt`:

```kotlin
package com.macrotrack.app.domain

import com.macrotrack.app.data.model.DailyLogStatus
import com.macrotrack.app.data.model.DailyTotals
import com.macrotrack.app.data.model.DayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ExpenditureRecordAssemblerTest {

    @Test
    fun aDayWithNothingLoggedBecomesUnlogged() {
        val day = LocalDate.of(2026, 8, 1)

        val result = ExpenditureRecordAssembler.assemble(
            statuses = emptyList(),
            totals = emptyList(),
            weightByDay = emptyMap(),
            start = day,
            end = day,
        )

        assertEquals(1, result.size)
        assertEquals(day, result[0].day)
        assertEquals(DayStatus.UNLOGGED, result[0].nutritionStatus)
        assertNull(result[0].calories)
        assertNull(result[0].weightKg)
    }

    @Test
    fun aDayWithAllThreeSourcesPresentCarriesAllThreeValues() {
        val day = LocalDate.of(2026, 8, 2)
        val statuses = listOf(DailyLogStatus(userId = "user-1", logDate = day.toString(), status = DayStatus.COMPLETE))
        val totals = listOf(
            DailyTotals(
                userId = "user-1", logDate = day.toString(), calories = 2100.0,
                proteinG = 160.0, carbsG = 220.0, fatG = 70.0, entryCount = 3,
            )
        )
        val weightByDay = mapOf(day to 81.4)

        val result = ExpenditureRecordAssembler.assemble(statuses, totals, weightByDay, day, day)

        assertEquals(1, result.size)
        assertEquals(DayStatus.COMPLETE, result[0].nutritionStatus)
        assertEquals(2100.0, result[0].calories!!, 0.001)
        assertEquals(81.4, result[0].weightKg!!, 0.001)
    }

    @Test
    fun aFastedDayKeepsItsExplicitZeroCalories() {
        val day = LocalDate.of(2026, 8, 3)
        val statuses = listOf(DailyLogStatus(userId = "user-1", logDate = day.toString(), status = DayStatus.FASTED))
        val totals = listOf(
            DailyTotals(
                userId = "user-1", logDate = day.toString(), calories = 0.0,
                proteinG = 0.0, carbsG = 0.0, fatG = 0.0, entryCount = 1,
            )
        )

        val result = ExpenditureRecordAssembler.assemble(statuses, totals, emptyMap(), day, day)

        assertEquals(DayStatus.FASTED, result[0].nutritionStatus)
        assertEquals(0.0, result[0].calories!!, 0.001)
        assertEquals(true, AdaptiveEngine.nutritionIsCountable(result[0]))
    }

    @Test
    fun aMultiDayRangeIsAssembledInAscendingOrder() {
        val day1 = LocalDate.of(2026, 8, 1)
        val day2 = LocalDate.of(2026, 8, 2)
        val day3 = LocalDate.of(2026, 8, 3)
        val statuses = listOf(
            DailyLogStatus(userId = "user-1", logDate = day1.toString(), status = DayStatus.COMPLETE),
            DailyLogStatus(userId = "user-1", logDate = day3.toString(), status = DayStatus.COMPLETE),
        )

        val result = ExpenditureRecordAssembler.assemble(statuses, emptyList(), emptyMap(), day1, day3)

        assertEquals(listOf(day1, day2, day3), result.map { it.day })
        assertEquals(DayStatus.COMPLETE, result[0].nutritionStatus)
        assertEquals(DayStatus.UNLOGGED, result[1].nutritionStatus) // day2 has no status row
        assertEquals(DayStatus.COMPLETE, result[2].nutritionStatus)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.ExpenditureRecordAssemblerTest"`
Expected: FAIL — `ExpenditureRecordAssembler` is an unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/macrotrack/app/domain/ExpenditureRecordAssembler.kt`:

```kotlin
package com.macrotrack.app.domain

import com.macrotrack.app.data.model.DailyLogStatus
import com.macrotrack.app.data.model.DailyTotals
import com.macrotrack.app.data.model.DayStatus
import java.time.LocalDate

/**
 * Combines per-day status, nutrition totals, and weight data into the dense,
 * one-row-per-calendar-day series `AdaptiveEngine.estimateExpenditure`
 * expects. A day with no status row is `DayStatus.UNLOGGED` (a missing row
 * and an explicit "unlogged" row are documented as equivalent -- see
 * `DayStatusModels.kt`); a day with no totals row has `calories = null`,
 * never a zero-fill, matching CLAUDE.md rule #2.
 */
object ExpenditureRecordAssembler {

    fun assemble(
        statuses: List<DailyLogStatus>,
        totals: List<DailyTotals>,
        weightByDay: Map<LocalDate, Double>,
        start: LocalDate,
        end: LocalDate,
    ): List<DailyRecord> {
        require(!end.isBefore(start)) { "end must not be before start, got start=$start end=$end" }
        val statusByDay = statuses.associate { LocalDate.parse(it.logDate) to it.status }
        val totalsByDay = totals.associate { LocalDate.parse(it.logDate) to it.calories }

        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        return days.map { day ->
            DailyRecord(
                day = day,
                calories = totalsByDay[day],
                weightKg = weightByDay[day],
                nutritionStatus = statusByDay[day] ?: DayStatus.UNLOGGED,
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.domain.ExpenditureRecordAssemblerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/domain/ExpenditureRecordAssembler.kt app/src/test/java/com/macrotrack/app/domain/ExpenditureRecordAssemblerTest.kt
git commit -m "feat: add ExpenditureRecordAssembler bridging per-day data sources to DailyRecord"
```

---

### Task 4: ExpenditureEstimateModels

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/model/ExpenditureEstimateModels.kt`
- Test: `app/src/test/java/com/macrotrack/app/data/model/ExpenditureEstimateModelsTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PersistedExpenditureEstimate` (decode model: `id`, `userId`, `windowStart: String`, `windowEnd: String`, `estimateKcal: Double`, `previousEstimateKcal: Double? = null`, `rawEstimateKcal: Double? = null`, `trendSlopeKgPerWeek: Double? = null`, `nutritionDays: Int`, `weightDays: Int`, `confidence: String`, `state: String`, `method: String`, `inputs: JsonObject`, `createdAt: String`) and `NewExpenditureEstimate` (insert payload: `userId`, `windowStart: String`, `windowEnd: String`, `estimateKcal: Double`, `previousEstimateKcal: Double? = null`, `rawEstimateKcal: Double? = null`, `trendSlopeKgPerWeek: Double? = null`, `nutritionDays: Int`, `weightDays: Int`, `confidence: String`, `state: String`, `inputs: JsonObject` — no default; `method`/`id`/`createdAt` are omitted, left to their schema defaults/server generation). Both `@Serializable` data classes in package `com.macrotrack.app.data.model`. Task 5 constructs `NewExpenditureEstimate` and decodes `PersistedExpenditureEstimate`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/macrotrack/app/data/model/ExpenditureEstimateModelsTest.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenditureEstimateModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAPersistedExpenditureEstimateRow() {
        val row = """
            {
              "id": "estimate-1",
              "user_id": "user-1",
              "window_start": "2026-07-01",
              "window_end": "2026-08-03",
              "estimate_kcal": 2450.0,
              "previous_estimate_kcal": 2400.0,
              "raw_estimate_kcal": 2460.5,
              "trend_slope_kg_per_week": -0.3,
              "nutrition_days": 12,
              "weight_days": 5,
              "confidence": "medium",
              "state": "updating",
              "method": "intake_minus_trend_energy",
              "inputs": {"explanation": "Expenditure updated from logged intake and smoothed weight trend."},
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val estimate = json.decodeFromString(PersistedExpenditureEstimate.serializer(), row)

        assertEquals("estimate-1", estimate.id)
        assertEquals("user-1", estimate.userId)
        assertEquals("2026-07-01", estimate.windowStart)
        assertEquals("2026-08-03", estimate.windowEnd)
        assertEquals(2450.0, estimate.estimateKcal, 0.001)
        assertEquals(2400.0, estimate.previousEstimateKcal!!, 0.001)
        assertEquals(-0.3, estimate.trendSlopeKgPerWeek!!, 0.001)
        assertEquals(12, estimate.nutritionDays)
        assertEquals(5, estimate.weightDays)
        assertEquals("medium", estimate.confidence)
        assertEquals("updating", estimate.state)
        assertEquals("intake_minus_trend_energy", estimate.method)
        assertEquals(
            "Expenditure updated from logged intake and smoothed weight trend.",
            estimate.inputs["explanation"]!!.toString().trim('"'),
        )
        assertEquals("2026-08-03T06:30:05Z", estimate.createdAt)
    }

    @Test
    fun decodesAHoldingRowWithNullOptionalFields() {
        val row = """
            {
              "id": "estimate-2",
              "user_id": "user-1",
              "window_start": "2026-07-01",
              "window_end": "2026-08-03",
              "estimate_kcal": 2400.0,
              "previous_estimate_kcal": null,
              "raw_estimate_kcal": null,
              "trend_slope_kg_per_week": null,
              "nutrition_days": 2,
              "weight_days": 1,
              "confidence": "low",
              "state": "holding",
              "method": "intake_minus_trend_energy",
              "inputs": {"explanation": "Nutrition logging is below the 6-of-7-day update gate."},
              "created_at": "2026-08-03T06:30:05Z"
            }
        """.trimIndent()

        val estimate = json.decodeFromString(PersistedExpenditureEstimate.serializer(), row)

        assertNull(estimate.previousEstimateKcal)
        assertNull(estimate.rawEstimateKcal)
        assertNull(estimate.trendSlopeKgPerWeek)
    }

    @Test
    fun encodesANewExpenditureEstimatePayloadIncludingInputsAndOmittingIdAndCreatedAt() {
        val payload = NewExpenditureEstimate(
            userId = "user-1",
            windowStart = "2026-07-01",
            windowEnd = "2026-08-03",
            estimateKcal = 2450.0,
            nutritionDays = 12,
            weightDays = 5,
            confidence = "medium",
            state = "updating",
            inputs = buildJsonObject { put("explanation", "Expenditure updated from logged intake and smoothed weight trend.") },
        )

        val encoded = json.encodeToString(NewExpenditureEstimate.serializer(), payload)

        // estimateKcal/nutritionDays/weightDays/confidence/state are required
        // (non-optional) fields, so they always encode regardless of
        // encodeDefaults -- safe to assert their presence.
        assertEquals(true, encoded.contains("\"estimate_kcal\":2450.0"))
        assertEquals(true, encoded.contains("\"nutrition_days\":12"))
        assertEquals(true, encoded.contains("\"confidence\":\"medium\""))
        assertEquals(true, encoded.contains("\"state\":\"updating\""))
        // inputs has no default value on NewExpenditureEstimate, so it always
        // encodes too, regardless of encodeDefaults.
        assertEquals(true, encoded.contains("Expenditure updated from logged intake"))
        assertEquals(false, encoded.contains("\"id\""))
        assertEquals(false, encoded.contains("\"created_at\""))
        assertEquals(false, encoded.contains("\"method\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.ExpenditureEstimateModelsTest"`
Expected: FAIL — `PersistedExpenditureEstimate`/`NewExpenditureEstimate` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/macrotrack/app/data/model/ExpenditureEstimateModels.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Mirrors `public.expenditure_estimates`. */
@Serializable
data class PersistedExpenditureEstimate(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("window_start") val windowStart: String,
    @SerialName("window_end") val windowEnd: String,
    @SerialName("estimate_kcal") val estimateKcal: Double,
    @SerialName("previous_estimate_kcal") val previousEstimateKcal: Double? = null,
    @SerialName("raw_estimate_kcal") val rawEstimateKcal: Double? = null,
    @SerialName("trend_slope_kg_per_week") val trendSlopeKgPerWeek: Double? = null,
    @SerialName("nutrition_days") val nutritionDays: Int,
    @SerialName("weight_days") val weightDays: Int,
    val confidence: String,
    val state: String,
    val method: String,
    val inputs: JsonObject,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Insert payload for a new expenditure estimate. `user_id` is filled in by
 * the repository from the current session, never trusted from the caller.
 * `id`/`created_at` are server-generated and `method` is left to the
 * schema's own default -- all three are omitted here. `inputs` has no
 * default: `ExpenditureEstimate.explanation` (no persisted column of its
 * own) is stored as `{"explanation": "..."}`, and this field must always be
 * transmitted, so it is a required constructor parameter rather than a
 * defaulted one (kotlinx.serialization's `encodeDefaults = false` would
 * silently drop a defaulted value).
 */
@Serializable
data class NewExpenditureEstimate(
    @SerialName("user_id") val userId: String,
    @SerialName("window_start") val windowStart: String,
    @SerialName("window_end") val windowEnd: String,
    @SerialName("estimate_kcal") val estimateKcal: Double,
    @SerialName("previous_estimate_kcal") val previousEstimateKcal: Double? = null,
    @SerialName("raw_estimate_kcal") val rawEstimateKcal: Double? = null,
    @SerialName("trend_slope_kg_per_week") val trendSlopeKgPerWeek: Double? = null,
    @SerialName("nutrition_days") val nutritionDays: Int,
    @SerialName("weight_days") val weightDays: Int,
    val confidence: String,
    val state: String,
    val inputs: JsonObject,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.ExpenditureEstimateModelsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/model/ExpenditureEstimateModels.kt app/src/test/java/com/macrotrack/app/data/model/ExpenditureEstimateModelsTest.kt
git commit -m "feat: add PersistedExpenditureEstimate/NewExpenditureEstimate models"
```

---

### Task 5: ExpenditureRepository

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt`

**Interfaces:**
- Consumes: `DayStatusRepository.listStatuses` (Task 1), `LogRepository.listDailyTotals` (Task 2), `ExpenditureRecordAssembler.assemble` (Task 3), `PersistedExpenditureEstimate`/`NewExpenditureEstimate` (Task 4), `WeightRepository.listEntries` (already exists), `WeightTrendCalculator.averageByLocalDay`/`WeightSample` (already exist), `AdaptiveEngine.estimateExpenditure`/`EngineConfig`/`ExpenditureEstimate` (already exist).
- Produces: `ExpenditureRepository` interface with `suspend fun getLatestEstimate(): PersistedExpenditureEstimate?` and `suspend fun recomputeExpenditure(): ExpenditureEstimate`; and `SupabaseExpenditureRepository(client: SupabaseClient, dayStatusRepository: DayStatusRepository, logRepository: LogRepository, weightRepository: WeightRepository) : ExpenditureRepository`. Task 6 (`AppContainer`) constructs `SupabaseExpenditureRepository(client, dayStatusRepository, logRepository, weightRepository)`.

This is a thin Postgrest I/O wrapper (plus calls into the pure Task 3 function and the already-built engine) — matching `TrendRepository`, it has no dedicated unit test in this plan (no live Supabase project exists in this sandbox; verified instead by static review against the real postgrest-kt 3.7.0 sources, same as prior slices).

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt`:

```kotlin
package com.macrotrack.app.data

import com.macrotrack.app.data.model.NewExpenditureEstimate
import com.macrotrack.app.data.model.PersistedExpenditureEstimate
import com.macrotrack.app.domain.AdaptiveEngine
import com.macrotrack.app.domain.EngineConfig
import com.macrotrack.app.domain.ExpenditureEstimate
import com.macrotrack.app.domain.ExpenditureRecordAssembler
import com.macrotrack.app.domain.WeightSample
import com.macrotrack.app.domain.WeightTrendCalculator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

interface ExpenditureRepository {
    suspend fun getLatestEstimate(): PersistedExpenditureEstimate?
    suspend fun recomputeExpenditure(): ExpenditureEstimate
}

class SupabaseExpenditureRepository(
    private val client: SupabaseClient,
    private val dayStatusRepository: DayStatusRepository,
    private val logRepository: LogRepository,
    private val weightRepository: WeightRepository,
) : ExpenditureRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("ExpenditureRepository used before a user session exists.")
    }

    override suspend fun getLatestEstimate(): PersistedExpenditureEstimate? {
        val userId = requireUserId()
        return client.postgrest.from("expenditure_estimates").select {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull<PersistedExpenditureEstimate>()
    }

    /**
     * Recomputes the current expenditure estimate from full logged history
     * and persists it, unless the engine had nothing concrete to report
     * (see this plan's Global Constraints for why that case is skipped
     * rather than given a sentinel). Always returns the engine's result,
     * whether or not a row was written.
     */
    override suspend fun recomputeExpenditure(): ExpenditureEstimate {
        val userId = requireUserId()
        val zoneId = ZoneId.systemDefault()
        val earliestBound = LocalDate.of(1970, 1, 1)

        val previous = getLatestEstimate()
        val statuses = dayStatusRepository.listStatuses(earliestBound)
        val totals = logRepository.listDailyTotals(earliestBound)
        val weightEntries = weightRepository.listEntries(Instant.EPOCH)
        val weightSamples = weightEntries.map { WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg) }
        val weightByDay = WeightTrendCalculator.averageByLocalDay(weightSamples, zoneId)

        val earliestDates = buildList {
            statuses.mapTo(this) { LocalDate.parse(it.logDate) }
            totals.mapTo(this) { LocalDate.parse(it.logDate) }
            addAll(weightByDay.keys)
        }
        val records = if (earliestDates.isEmpty()) {
            emptyList()
        } else {
            val start = earliestDates.min()
            val end = LocalDate.now(zoneId)
            ExpenditureRecordAssembler.assemble(statuses, totals, weightByDay, start, end)
        }

        val estimate = AdaptiveEngine.estimateExpenditure(records, previous?.estimateKcal, EngineConfig())

        val estimateKcal = estimate.estimateKcal
        val windowStart = estimate.windowStart
        val windowEnd = estimate.windowEnd
        if (estimateKcal != null && windowStart != null && windowEnd != null) {
            val payload = NewExpenditureEstimate(
                userId = userId,
                windowStart = windowStart,
                windowEnd = windowEnd,
                estimateKcal = estimateKcal,
                previousEstimateKcal = estimate.previousEstimateKcal,
                rawEstimateKcal = estimate.rawEstimateKcal,
                trendSlopeKgPerWeek = estimate.trendSlopeKgPerWeek,
                nutritionDays = estimate.nutritionDays,
                weightDays = estimate.weightDays,
                confidence = estimate.confidence,
                state = estimate.state,
                inputs = buildJsonObject { put("explanation", estimate.explanation) },
            )
            client.postgrest.from("expenditure_estimates").insert(payload)
        }

        return estimate
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compiles cleanly (or, if the Android SDK is unavailable in this sandbox, say so explicitly and rely on the manual API-surface review below instead of claiming this ran).

Manual verification performed in place of a live compile (record in the task report): `client.postgrest.from(table).insert(payload)` (single-value `insert`, no `select()`/no captured result — this repository never needs the inserted row back) matches `PostgrestQueryBuilder.insert(value: T, request: InsertRequestBuilder.() -> Unit = {})` from the real postgrest-kt 3.7.0 sources, already used identically elsewhere in this codebase (e.g. `FavoritesRepository.addFood`). `order("created_at", Order.DESCENDING)` followed by `limit(1)` then `decodeSingleOrNull<PersistedExpenditureEstimate>()` matches the same select-then-decode pattern already verified in `TrendRepository`/`DayStatusRepository`. Every `Instant`-to-`LocalDate` conversion in this file uses `atZone(zoneId).toLocalDate()`/`LocalDate.now(zoneId)`, never `LocalDate.ofInstant`. `earliestDates.min()` is `kotlin.collections.min()` on a non-empty `List<LocalDate>` (guarded by the `earliestDates.isEmpty()` check immediately above), not the deprecated-nullable `minOrNull()`, and `LocalDate` implements `Comparable<LocalDate>` so no custom comparator is needed.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt
git commit -m "feat: add ExpenditureRepository computing and persisting expenditure_estimates from full logged history"
```

---

### Task 6: AppContainer wiring and gaps documentation

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`
- Create: `docs/EXPENDITURE_STATE_GAPS.md`

**Interfaces:**
- Consumes: `ExpenditureRepository`/`SupabaseExpenditureRepository` from Task 5.
- Produces: `AppContainer.expenditureRepository: ExpenditureRepository`, available to any future caller (e.g. a weekly-check-in or expenditure-history ViewModel) the same way `AppContainer.trendRepository` already is.

- [ ] **Step 1: Add the wiring**

In `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`, add one line after `trendRepository`:

```kotlin
    val expenditureRepository: ExpenditureRepository by lazy {
        SupabaseExpenditureRepository(client, dayStatusRepository, logRepository, weightRepository)
    }
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
    val expenditureRepository: ExpenditureRepository by lazy {
        SupabaseExpenditureRepository(client, dayStatusRepository, logRepository, weightRepository)
    }
}
```

- [ ] **Step 2: Write the gaps documentation**

Create `docs/EXPENDITURE_STATE_GAPS.md`:

```markdown
# Expenditure state — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From building
`docs/superpowers/plans/2026-08-03-expenditure-state.md` (branch
`claude/macro-factor-app-dev-6twv5o`), which resolved
`docs/ADAPTIVE_ENGINE_GAPS.md`'s "Nullable engine fields vs. NOT NULL schema
columns" gap.

## Resolution: when a row is persisted

`ExpenditureRepository.recomputeExpenditure` only inserts into
`expenditure_estimates` when `estimate.estimateKcal`, `estimate.windowStart`,
and `estimate.windowEnd` are all non-null. Per `AdaptiveEngine.estimateExpenditure`,
that excludes exactly two cases: a completely empty `records` list, and a
holding result with no `previousEstimateKcal` to carry forward (the very
first-ever recompute with insufficient data). Every other holding or
updating result has all three fields defined and is persisted normally.
This mirrors this repo's existing precedent (`daily_nutrition_totals`,
`weight_trend_points`) that absence of a row is how "nothing to report" is
represented -- never a fabricated sentinel value. A caller finding no
persisted row means "never enough information to say anything, ever";
CLAUDE.md's "missing-data holding is a valid state and must be visible" is
satisfied because ordinary holding-with-a-carried-number IS persisted.

## Resolution: `inputs` jsonb contract

`expenditure_estimates.inputs` (`jsonb not null default '{}'::jsonb`) had no
defined contract anywhere in this repo's source-of-truth docs before this
slice -- `docs/ADAPTIVE_ENGINE_CONTRACT.md` and `adaptive_engine.py` never
mention it. Separately, the table has **no `explanation` text column** at
all, even though `ExpenditureEstimate.explanation` is a real, always-computed
human-readable field. This slice's resolution: `inputs` stores exactly
`{"explanation": "<ExpenditureEstimate.explanation>"}`. This is this
repo's explicit product choice for this slice, not an inherited spec --
record it here rather than let a future reader assume `inputs` has a richer
or different contract than it actually does.

## Still open: no scheduling/trigger for `recomputeExpenditure`

Like `TrendRepository.recomputeTrend`, `ExpenditureRepository.recomputeExpenditure`
is entirely caller-driven -- nothing in this slice calls it. Whoever wires up
the first caller needs to decide when recomputation happens: on app open, on
a schedule, after every log/weigh-in, or on-demand when an expenditure
screen opens. Not attempted here -- this slice is data-layer only.

## Still open: no test exercises `recomputeExpenditure` end-to-end

Like `TrendRepository`, `ExpenditureRepository` has no dedicated unit test --
there is no mock/fake Postgrest client in this codebase, and no live
Supabase project exists in this sandbox. The persist/skip decision and the
full-history-fetch behavior are covered by manual review and by the
already-tested pure functions it composes (`ExpenditureRecordAssembler`,
`AdaptiveEngine.estimateExpenditure`), but the wiring between them is not
independently tested. Worth revisiting if this codebase ever adds a fake
Postgrest client for repository-level tests.
```

- [ ] **Step 3: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all existing tests plus `ExpenditureRecordAssemblerTest`/`ExpenditureEstimateModelsTest` green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/AppContainer.kt docs/EXPENDITURE_STATE_GAPS.md
git commit -m "feat: wire ExpenditureRepository into AppContainer; document expenditure-state gaps"
```

---

## Self-Review

**1. Spec coverage:** `DayStatusRepository.listStatuses` (Task 1) ✅. `LogRepository.listDailyTotals`, reusing the existing false-zero-row guard (Task 2) ✅. `ExpenditureRecordAssembler` with the four required test cases (nothing logged, all three sources present, fasted-day explicit zero, multi-day ordering) (Task 3) ✅. `PersistedExpenditureEstimate`/`NewExpenditureEstimate` models, `inputs` required (not defaulted) on the insert payload (Task 4) ✅. `ExpenditureRepository` fetching full history (not a caller-bounded window), persist-only-when-real-numbers-exist policy, `inputs` = `{"explanation": ...}` (Task 5) ✅. `AppContainer` wiring and `docs/EXPENDITURE_STATE_GAPS.md` recording both resolved product decisions and the two still-open gaps (Task 6) ✅. UI/weekly-check-in/`macro_programs`/`user_nutrient_targets` explicitly excluded ✅.

**2. Placeholder scan:** No TBD/TODO; all code blocks are complete; no "similar to Task N" references — none found.

**3. Type consistency:** `DailyLogStatus`/`DailyTotals` (Tasks 1-2, pre-existing) match `ExpenditureRecordAssembler.assemble`'s parameter types exactly (Task 3). `ExpenditureRecordAssembler.assemble(...): List<DailyRecord>` matches `AdaptiveEngine.estimateExpenditure(records: List<DailyRecord>, ...)`'s parameter type (Task 5 calls both in sequence). `PersistedExpenditureEstimate.estimateKcal: Double` (Task 4) matches `previous?.estimateKcal` used as `estimateExpenditure`'s `previousEstimateKcal: Double?` argument (Task 5). `NewExpenditureEstimate`'s field names/types (Task 4) match every value pulled off `estimate: ExpenditureEstimate` in Task 5's payload construction (`estimate.previousEstimateKcal`, `estimate.rawEstimateKcal`, `estimate.trendSlopeKgPerWeek`, `estimate.nutritionDays`, `estimate.weightDays`, `estimate.confidence`, `estimate.state`, `estimate.explanation`). `AppContainer.expenditureRepository`'s declared type `ExpenditureRepository` and constructor `SupabaseExpenditureRepository(client, dayStatusRepository, logRepository, weightRepository)` (Task 6) match Task 5's produced interface/impl names and constructor signature exactly.
