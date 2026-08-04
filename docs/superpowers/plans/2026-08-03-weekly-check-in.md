# Weekly Check-In Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist `WeeklyCheckIn.weeklyCheckIn`'s output into `weekly_check_ins`, closing `docs/ADAPTIVE_ENGINE_GAPS.md`'s "ready status has no home" gap, and expose the accept/decline flow the schema already models.

**Architecture:** `ExpenditureRepository` gains a `loadRecords()` method (a pure refactor extracting its existing full-history fetch + damping-anchor logic) so `CheckInRepository` can reuse the exact same records/anchor rather than re-deriving them. `CheckInRepository` combines that with the user's latest weigh-in, calls `weeklyCheckIn`, maps its vocabulary onto the schema's, and upserts by `(user_id, week_start)` — this table has a real uniqueness constraint, unlike `expenditure_estimates`'s append-only history.

**Tech Stack:** Kotlin, `java.time`, kotlinx.serialization (including `kotlinx.serialization.json.JsonArray`/`Json.encodeToJsonElement`), `io.github.jan-tennert.supabase` postgrest-kt/auth-kt 3.7.0, JUnit4.

> **Post-implementation note (CLAUDE.md: "if implementation and documentation
> disagree, stop and reconcile"):** this plan's original Task 2/Task 3 code
> sketches (`NewCheckIn`'s numeric fields, `observedExpenditureKcal`, and the
> `buildJsonArray`/`buildJsonObject` module-encoding shown below) were revised
> during Task 3's review and a follow-up fix commit, before that fix's own
> re-review approved the branch. The code sketches further down in this
> document are the *original* proposal and are kept for historical record —
> they do not match what actually shipped. The real, correct behavior is:
> - `NewCheckIn`'s seven numeric fields and `resolvedAt` have **no** Kotlin
>   default (required constructor parameters, always transmitted explicitly,
>   including explicit `null`) — not `= null` as sketched below. A defaulted
>   `null` would have been silently omitted by kotlinx.serialization's
>   `encodeDefaults = false` and left a stale prior value in place across an
>   upsert instead of clearing it.
> - `observedExpenditureKcal` reads `result.estimate.rawEstimateKcal`, not
>   `result.estimate.estimateKcal` as sketched below — `estimateKcal` is the
>   carried-forward anchor on a holding result, not anything observed that
>   week; `rawEstimateKcal` is null on exactly the paths where nothing was
>   really observed.
> - `modules` is built via `Json.encodeToJsonElement(result.modules.map { CheckInModuleDto(...) }).jsonArray`,
>   not hand-built `buildJsonArray`/`buildJsonObject` calls, so the decode
>   side (`PersistedCheckIn.modules: List<CheckInModuleDto>`) and the write
>   side can't drift on field names.
>
> See `app/src/main/java/com/macrotrack/app/data/model/CheckInModels.kt` and
> `app/src/main/java/com/macrotrack/app/data/CheckInRepository.kt` for the
> actual shipped code, and `docs/WEEKLY_CHECKIN_GAPS.md` for the full
> incident and remaining open questions.

## Global Constraints

- `weekly_check_ins` schema (`supabase/migrations/001_macro_foundation.sql:280-299`): `id uuid primary key default gen_random_uuid()`, `user_id uuid not null`, `program_id uuid references macro_programs(id) on delete set null` (nullable — always `null` in this plan, `macro_programs` is out of scope), `week_start date not null`, `week_end date not null`, `status text not null check (status in ('pending','held','accepted','declined'))`, `previous_expenditure_kcal numeric`, `observed_expenditure_kcal numeric`, `proposed_expenditure_kcal numeric`, `proposed_calories numeric`, `proposed_protein_g numeric`, `proposed_carbs_g numeric`, `proposed_fat_g numeric`, `modules jsonb not null default '[]'::jsonb`, `explanation text not null`, `created_at timestamptz not null default now()`, `resolved_at timestamptz`, **`unique (user_id, week_start)`**. RLS policy `checkin_owner_all`: `for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())`.
- The `unique (user_id, week_start)` constraint makes this table upsert-by-key, unlike `expenditure_estimates` (append-only, no such constraint). Persist via `client.postgrest.from(table).upsert(payload) { select() }.decodeSingle<T>()` — the same pattern already verified and used in `DayStatusRepository.setStatus`/`TrendRepository.recomputeTrend` — never the plain `insert()` `ExpenditureRepository` uses for its own, different table shape.
- `CheckInResult.status` ("held" | "ready") does not match the schema's vocabulary ("pending","held","accepted","declined"). Map explicitly: `"held"` → `"held"`, `"ready"` → `"pending"` (awaiting the user's accept/decline). This is `docs/ADAPTIVE_ENGINE_GAPS.md`'s documented gap, resolved here.
- `calorieTarget(...)`'s return value becomes both `weekly_check_ins.proposed_expenditure_kcal` and (unchanged, via `macroTargets(calories, ...)`) `MacroTargets.calories`/`proposed_calories` — this is one number under two column names by the engine's own design (`weeklyCheckIn` passes the same `calories` local into both `calorieTarget` and `macroTargets`), not two independently derived values. Store `checkInResult.targets?.calories` in both columns.
- `targetRateKgPerWeek` has no real source in this codebase yet (`macro_programs` is out of scope for every slice on this branch, including this one). `CheckInRepository.recomputeCheckIn(...)` takes it as a **required caller-supplied parameter**, never fetched from a table or defaulted to an invented rate.
- `bodyWeightKg` DOES have a real source: the most recent entry from `WeightRepository.listEntries(Instant.EPOCH)`, by `measuredAt`. If no weigh-ins exist at all, `recomputeCheckIn` throws `IllegalStateException("recomputeCheckIn requires at least one logged weigh-in")` rather than inventing a placeholder weight — a real precondition a future UI must guard, not something to paper over.
- `previousExpenditureKcal` (an argument `weeklyCheckIn` expects) is exactly `ExpenditureRepository.loadRecords()`'s second tuple element (the damping anchor) — reused directly, never independently re-derived.
- `records` (an argument `weeklyCheckIn` expects) is exactly `ExpenditureRepository.loadRecords()`'s first tuple element — the same full-history dense series expenditure-state itself uses. Never a second, separately-fetched version.
- `resolve(weekStart, accepted)` only succeeds on a row currently `"pending"` — a missing row, an already-resolved row, or a `"held"` row (which has no targets to accept) all fail loudly via `error(...)`, never silently.
- Any `Instant`-to-`LocalDate` conversion uses `instant.atZone(zoneId).toLocalDate()`, **never** `LocalDate.ofInstant(instant, zoneId)` (a Java 9 addition; `minSdk` is 26, no core-library desugaring — this exact mistake caused a real Critical bug in the trend-visualisation slice).
- A `filter { }` block never applies two direct conditions to the same Postgrest column — a verified Critical bug class from the trend-visualisation slice's final review (postgrest-kt's top-level params are keyed by column and folded to their first value only). This plan's queries only need single-column-single-condition filters, so this shouldn't arise, but if it ever does, use `and { }` grouping (see `TrendRepository.recomputeTrend` for the verified pattern), never two direct calls.
- Every repository's `requireUserId()` calls `client.auth.awaitInitialization()` before `client.auth.currentUserOrNull()`.
- Reuse the verified Postgrest DSL surface (`select`/`filter`/`eq`/`order`/`upsert`/`update`, `decodeSingle`/`decodeSingleOrNull`) from prior slices — don't re-derive it.
- Out of scope: any UI, `macro_programs`/`macro_program_days`/`user_nutrient_targets` tables, `program_update` module emission (a separate, already-documented gap).

---

### Task 1: Refactor ExpenditureRepository to expose loadRecords()

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt`

**Interfaces:**
- Consumes: nothing new — this is a pure refactor of already-committed code.
- Produces: `ExpenditureRepository.loadRecords(): Pair<List<DailyRecord>, Double?>` (records to damping anchor), added to both the interface and `SupabaseExpenditureRepository`. Task 3 (`CheckInRepository`) calls this.

This is the single riskiest task in this plan: `recomputeExpenditure()` already went through three review rounds finding real bugs (a Critical per-invocation damping-chain drift, an Important same-day staleness bug in the first fix, and a fasted-day counting bug found in final review). This task must not change any of that function's *external* behavior — it only extracts an existing block of logic into a new method and calls that method instead of inlining it.

- [ ] **Step 1: Read the current file in full**

Read `app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt` before editing. Its current `recomputeExpenditure()` (as of this plan) is:

```kotlin
    override suspend fun recomputeExpenditure(): ExpenditureEstimate {
        val userId = requireUserId()
        val zoneId = ZoneId.systemDefault()
        val earliestBound = LocalDate.of(1970, 1, 1)
        val today = LocalDate.now(zoneId)

        val previous = getLatestEstimate()
        val isPreviousFromToday = previous != null && previous.windowEnd == today.toString()
        val dampingAnchor = when {
            previous == null -> null
            isPreviousFromToday -> previous.previousEstimateKcal
            else -> previous.estimateKcal
        }

        val statuses = dayStatusRepository.listStatuses(earliestBound)
        val totals = logRepository.listDailyTotals(earliestBound)
        val weightEntries = weightRepository.listEntries(Instant.EPOCH)
        val weightSamples = weightEntries.map { WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg) }
        val weightByDay = WeightTrendCalculator.averageByLocalDay(weightSamples, zoneId)

        val allKnownDates = buildList {
            statuses.mapTo(this) { LocalDate.parse(it.logDate) }
            totals.mapTo(this) { LocalDate.parse(it.logDate) }
            addAll(weightByDay.keys)
        }
        val records = if (allKnownDates.isEmpty() || allKnownDates.min().isAfter(today)) {
            emptyList()
        } else {
            ExpenditureRecordAssembler.assemble(statuses, totals, weightByDay, allKnownDates.min(), today)
        }

        val estimate = AdaptiveEngine.estimateExpenditure(records, dampingAnchor, EngineConfig())

        val estimateKcal = estimate.estimateKcal
        val windowStart = estimate.windowStart
        val windowEnd = estimate.windowEnd
        if (estimateKcal != null && windowStart != null && windowEnd != null) {
            if (isPreviousFromToday) {
                client.postgrest.from("expenditure_estimates").delete {
                    filter {
                        eq("user_id", userId)
                        eq("id", previous.id)
                    }
                }
            }
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
```

(There are also two comments in the current file above `order("id", Order.DESCENDING)` and above the `isPreviousFromToday` line — leave those exactly where they are relative to the lines they annotate.)

- [ ] **Step 2: Add `loadRecords()` to the interface**

```kotlin
interface ExpenditureRepository {
    suspend fun getLatestEstimate(): PersistedExpenditureEstimate?
    suspend fun recomputeExpenditure(): ExpenditureEstimate
    suspend fun loadRecords(): Pair<List<DailyRecord>, Double?>
}
```

Add `import com.macrotrack.app.domain.DailyRecord` to the imports (not currently imported — `ExpenditureEstimate`/`EngineConfig` etc. already are).

- [ ] **Step 3: Extract the logic into `loadRecords()`, and call it from `recomputeExpenditure()`**

Replace the body of `recomputeExpenditure()` with:

```kotlin
    override suspend fun loadRecords(): Pair<List<DailyRecord>, Double?> {
        val zoneId = ZoneId.systemDefault()
        val earliestBound = LocalDate.of(1970, 1, 1)
        val today = LocalDate.now(zoneId)

        val previous = getLatestEstimate()
        val dampingAnchor = when {
            previous == null -> null
            previous.windowEnd == today.toString() -> previous.previousEstimateKcal
            else -> previous.estimateKcal
        }

        val statuses = dayStatusRepository.listStatuses(earliestBound)
        val totals = logRepository.listDailyTotals(earliestBound)
        val weightEntries = weightRepository.listEntries(Instant.EPOCH)
        val weightSamples = weightEntries.map { WeightSample(OffsetDateTime.parse(it.measuredAt).toInstant(), it.weightKg) }
        val weightByDay = WeightTrendCalculator.averageByLocalDay(weightSamples, zoneId)

        val allKnownDates = buildList {
            statuses.mapTo(this) { LocalDate.parse(it.logDate) }
            totals.mapTo(this) { LocalDate.parse(it.logDate) }
            addAll(weightByDay.keys)
        }
        val records = if (allKnownDates.isEmpty() || allKnownDates.min().isAfter(today)) {
            emptyList()
        } else {
            ExpenditureRecordAssembler.assemble(statuses, totals, weightByDay, allKnownDates.min(), today)
        }

        return records to dampingAnchor
    }

    override suspend fun recomputeExpenditure(): ExpenditureEstimate {
        val userId = requireUserId()
        val today = LocalDate.now(ZoneId.systemDefault())

        val previous = getLatestEstimate()
        val isPreviousFromToday = previous != null && previous.windowEnd == today.toString()
        val (records, dampingAnchor) = loadRecords()

        val estimate = AdaptiveEngine.estimateExpenditure(records, dampingAnchor, EngineConfig())

        val estimateKcal = estimate.estimateKcal
        val windowStart = estimate.windowStart
        val windowEnd = estimate.windowEnd
        if (estimateKcal != null && windowStart != null && windowEnd != null) {
            if (isPreviousFromToday) {
                client.postgrest.from("expenditure_estimates").delete {
                    filter {
                        eq("user_id", userId)
                        eq("id", previous.id)
                    }
                }
            }
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
```

`getLatestEstimate()` is now called twice per `recomputeExpenditure()` invocation (once directly, once inside `loadRecords()`) — this is a deliberate, accepted small inefficiency to keep the two concerns cleanly separated: `loadRecords()` is "what anchor should any caller damp from" (shared with `CheckInRepository`), while `recomputeExpenditure()`'s own `previous`/`isPreviousFromToday` is "should I replace today's already-persisted row" (a daily-persistence-only concern that a weekly check-in has no equivalent of). Do not try to eliminate the duplicate call by threading `previous` through `loadRecords()`'s signature — that would leak the daily-recompute-specific `isPreviousFromToday` concept into a method meant to be reused by a different persistence shape.

**Preserve the comments** already in the file above `order("id", Order.DESCENDING)` (the "not a recency ordering" one) and above the original `isPreviousFromToday` line (the "windowEnd always equals today" one) — move the latter to sit directly above its line inside `loadRecords()`, since that's where the invariant it describes now lives.

- [ ] **Step 4: Verify external behavior is unchanged**

This has no dedicated unit test (matching `ExpenditureRepository`'s existing precedent — no live Supabase project in this sandbox). Instead, do a careful side-by-side trace: read the *before* code from Step 1 and the *after* code from Step 3, and confirm line-by-line that every computation `recomputeExpenditure()` performs is identical — same `today`, same `dampingAnchor` formula, same `records` construction, same persist/replace/insert logic — just relocated. The only genuinely new thing is the second `getLatestEstimate()` call. Record this trace in your task report.

Attempt `./gradlew :app:compileDebugKotlin` (expect it may be infeasible in this sandbox — a known, standing limitation; say so explicitly rather than fabricating a passing run).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt
git commit -m "refactor: expose ExpenditureRepository.loadRecords() for reuse by the weekly check-in slice"
```

---

### Task 2: CheckInModels

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/model/CheckInModels.kt`
- Test: `app/src/test/java/com/macrotrack/app/data/model/CheckInModelsTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `CheckInModuleDto(key: String, action: String)` (a serializable mirror of the domain `CheckInModule`, for jsonb encoding — `app/src/main/java/com/macrotrack/app/domain/WeeklyCheckIn.kt`'s `CheckInModule` is not itself `@Serializable`), `PersistedCheckIn` (decode model mirroring the full `weekly_check_ins` row), `NewCheckIn` (upsert payload). All three `@Serializable` data classes in package `com.macrotrack.app.data.model`. Task 3 constructs `NewCheckIn`/`CheckInModuleDto` and decodes `PersistedCheckIn`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/macrotrack/app/data/model/CheckInModelsTest.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckInModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAPersistedCheckInRow() {
        val row = """
            {
              "id": "checkin-1",
              "user_id": "user-1",
              "program_id": null,
              "week_start": "2026-07-27",
              "week_end": "2026-08-02",
              "status": "pending",
              "previous_expenditure_kcal": 2400.0,
              "observed_expenditure_kcal": 2450.0,
              "proposed_expenditure_kcal": 2250.0,
              "proposed_calories": 2250.0,
              "proposed_protein_g": 160.0,
              "proposed_carbs_g": 200.0,
              "proposed_fat_g": 70.0,
              "modules": [],
              "explanation": "The next target uses observed expenditure and the signed goal rate.",
              "created_at": "2026-08-03T06:30:05Z",
              "resolved_at": null
            }
        """.trimIndent()

        val checkIn = json.decodeFromString(PersistedCheckIn.serializer(), row)

        assertEquals("checkin-1", checkIn.id)
        assertEquals("user-1", checkIn.userId)
        assertNull(checkIn.programId)
        assertEquals("2026-07-27", checkIn.weekStart)
        assertEquals("2026-08-02", checkIn.weekEnd)
        assertEquals("pending", checkIn.status)
        assertEquals(2400.0, checkIn.previousExpenditureKcal!!, 0.001)
        assertEquals(2250.0, checkIn.proposedCalories!!, 0.001)
        assertEquals(0, checkIn.modules.size)
        assertNull(checkIn.resolvedAt)
    }

    @Test
    fun decodesAHeldRowWithNullProposedFields() {
        val row = """
            {
              "id": "checkin-2",
              "user_id": "user-1",
              "program_id": null,
              "week_start": "2026-07-20",
              "week_end": "2026-07-26",
              "status": "held",
              "previous_expenditure_kcal": null,
              "observed_expenditure_kcal": null,
              "proposed_expenditure_kcal": null,
              "proposed_calories": null,
              "proposed_protein_g": null,
              "proposed_carbs_g": null,
              "proposed_fat_g": null,
              "modules": [{"key": "weigh_in", "action": "add a weigh-in for each seven-day period"}],
              "explanation": "More history is required before updating expenditure.",
              "created_at": "2026-07-26T06:30:05Z",
              "resolved_at": null
            }
        """.trimIndent()

        val checkIn = json.decodeFromString(PersistedCheckIn.serializer(), row)

        assertEquals("held", checkIn.status)
        assertNull(checkIn.proposedCalories)
        assertEquals(1, checkIn.modules.size)
        assertEquals("weigh_in", checkIn.modules[0].key)
    }

    @Test
    fun encodesANewCheckInPayloadOmittingIdAndCreatedAtAndResolvedAt() {
        val payload = NewCheckIn(
            userId = "user-1",
            weekStart = "2026-07-27",
            weekEnd = "2026-08-02",
            status = "pending",
            previousExpenditureKcal = 2400.0,
            observedExpenditureKcal = 2450.0,
            proposedExpenditureKcal = 2250.0,
            proposedCalories = 2250.0,
            proposedProteinG = 160.0,
            proposedCarbsG = 200.0,
            proposedFatG = 70.0,
            modules = buildJsonArray {
                add(buildJsonObject { put("key", "weigh_in"); put("action", "add a weigh-in") })
            },
            explanation = "The next target uses observed expenditure and the signed goal rate.",
        )

        val encoded = json.encodeToString(NewCheckIn.serializer(), payload)

        // week_start/week_end/status/explanation/modules are all required
        // (non-optional) fields, so they always encode regardless of
        // encodeDefaults -- safe to assert their presence.
        assertEquals(true, encoded.contains("\"week_start\":\"2026-07-27\""))
        assertEquals(true, encoded.contains("\"status\":\"pending\""))
        assertEquals(true, encoded.contains("\"weigh_in\""))
        assertEquals(false, encoded.contains("\"id\""))
        assertEquals(false, encoded.contains("\"created_at\""))
        assertEquals(false, encoded.contains("\"resolved_at\""))
    }

    @Test
    fun checkInModuleDtoRoundTrips() {
        val dto = CheckInModuleDto(key = "logging_break", action = "carry forward the last high-confidence estimate")

        val encoded = json.encodeToString(CheckInModuleDto.serializer(), dto)
        val decoded = json.decodeFromString(CheckInModuleDto.serializer(), encoded)

        assertEquals(dto, decoded)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.CheckInModelsTest"`
Expected: FAIL — `PersistedCheckIn`/`NewCheckIn`/`CheckInModuleDto` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/macrotrack/app/data/model/CheckInModels.kt`:

```kotlin
package com.macrotrack.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/** Serializable mirror of the domain `CheckInModule` (WeeklyCheckIn.kt), for jsonb encoding. */
@Serializable
data class CheckInModuleDto(
    val key: String,
    val action: String,
)

/** Mirrors `public.weekly_check_ins`. */
@Serializable
data class PersistedCheckIn(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("program_id") val programId: String? = null,
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    val status: String,
    @SerialName("previous_expenditure_kcal") val previousExpenditureKcal: Double? = null,
    @SerialName("observed_expenditure_kcal") val observedExpenditureKcal: Double? = null,
    @SerialName("proposed_expenditure_kcal") val proposedExpenditureKcal: Double? = null,
    @SerialName("proposed_calories") val proposedCalories: Double? = null,
    @SerialName("proposed_protein_g") val proposedProteinG: Double? = null,
    @SerialName("proposed_carbs_g") val proposedCarbsG: Double? = null,
    @SerialName("proposed_fat_g") val proposedFatG: Double? = null,
    val modules: List<CheckInModuleDto>,
    val explanation: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

/**
 * Upsert payload for a recomputed check-in. `user_id` is filled in by the
 * repository from the current session, never trusted from the caller.
 * `id`/`created_at` are server-generated; `program_id` is always null in
 * this slice (`macro_programs` is out of scope); `resolved_at` is always
 * null on an initial recompute (only `CheckInRepository.resolve` sets it) --
 * all three are omitted here rather than defaulted, so kotlinx.serialization
 * never has a default value to silently drop on this side.
 */
@Serializable
data class NewCheckIn(
    @SerialName("user_id") val userId: String,
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    val status: String,
    @SerialName("previous_expenditure_kcal") val previousExpenditureKcal: Double? = null,
    @SerialName("observed_expenditure_kcal") val observedExpenditureKcal: Double? = null,
    @SerialName("proposed_expenditure_kcal") val proposedExpenditureKcal: Double? = null,
    @SerialName("proposed_calories") val proposedCalories: Double? = null,
    @SerialName("proposed_protein_g") val proposedProteinG: Double? = null,
    @SerialName("proposed_carbs_g") val proposedCarbsG: Double? = null,
    @SerialName("proposed_fat_g") val proposedFatG: Double? = null,
    val modules: JsonArray,
    val explanation: String,
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macrotrack.app.data.model.CheckInModelsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/model/CheckInModels.kt app/src/test/java/com/macrotrack/app/data/model/CheckInModelsTest.kt
git commit -m "feat: add CheckInModuleDto/PersistedCheckIn/NewCheckIn models"
```

---

### Task 3: CheckInRepository

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/CheckInRepository.kt`

**Interfaces:**
- Consumes: `CheckInModuleDto`/`PersistedCheckIn`/`NewCheckIn` (Task 2, `com.macrotrack.app.data.model`); `ExpenditureRepository.loadRecords()` (Task 1); `WeightRepository.listEntries` (already exists); `WeeklyCheckIn.weeklyCheckIn`/`CheckInResult`/`CheckInModule` (already exists, `com.macrotrack.app.domain`); `EngineConfig` (already exists).
- Produces: `CheckInRepository` interface with `suspend fun getCheckIn(weekStart: LocalDate): PersistedCheckIn?`, `suspend fun recomputeCheckIn(weekStart: LocalDate, weekEnd: LocalDate, targetRateKgPerWeek: Double, proteinGPerKg: Double = EngineConfig().defaultProteinGPerKg, fatGPerKg: Double = EngineConfig().defaultFatGPerKg): CheckInResult`, and `suspend fun resolve(weekStart: LocalDate, accepted: Boolean): PersistedCheckIn`; and `SupabaseCheckInRepository(client: SupabaseClient, expenditureRepository: ExpenditureRepository, weightRepository: WeightRepository) : CheckInRepository`. Task 4 (`AppContainer`) constructs `SupabaseCheckInRepository(client, expenditureRepository, weightRepository)`.

This is a thin Postgrest I/O wrapper (plus calls into already-built pure functions) — matching `ExpenditureRepository`/`TrendRepository`, no dedicated unit test in this plan (no live Supabase project in this sandbox; verified by static review against the real postgrest-kt 3.7.0 sources, same as prior slices).

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/macrotrack/app/data/CheckInRepository.kt`:

```kotlin
package com.macrotrack.app.data

import com.macrotrack.app.data.model.CheckInModuleDto
import com.macrotrack.app.data.model.NewCheckIn
import com.macrotrack.app.data.model.PersistedCheckIn
import com.macrotrack.app.domain.CheckInResult
import com.macrotrack.app.domain.EngineConfig
import com.macrotrack.app.domain.weeklyCheckIn
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

interface CheckInRepository {
    suspend fun getCheckIn(weekStart: LocalDate): PersistedCheckIn?
    suspend fun recomputeCheckIn(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        targetRateKgPerWeek: Double,
        proteinGPerKg: Double = EngineConfig().defaultProteinGPerKg,
        fatGPerKg: Double = EngineConfig().defaultFatGPerKg,
    ): CheckInResult
    suspend fun resolve(weekStart: LocalDate, accepted: Boolean): PersistedCheckIn
}

class SupabaseCheckInRepository(
    private val client: SupabaseClient,
    private val expenditureRepository: ExpenditureRepository,
    private val weightRepository: WeightRepository,
) : CheckInRepository {

    private suspend fun requireUserId(): String {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.id
            ?: error("CheckInRepository used before a user session exists.")
    }

    override suspend fun getCheckIn(weekStart: LocalDate): PersistedCheckIn? {
        val userId = requireUserId()
        return client.postgrest.from("weekly_check_ins").select {
            filter {
                eq("user_id", userId)
                eq("week_start", weekStart.toString())
            }
            limit(1)
        }.decodeSingleOrNull<PersistedCheckIn>()
    }

    override suspend fun recomputeCheckIn(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        targetRateKgPerWeek: Double,
        proteinGPerKg: Double,
        fatGPerKg: Double,
    ): CheckInResult {
        val userId = requireUserId()

        val weightEntries = weightRepository.listEntries(Instant.EPOCH)
        val latestWeighIn = weightEntries.maxByOrNull { OffsetDateTime.parse(it.measuredAt).toInstant() }
            ?: error("recomputeCheckIn requires at least one logged weigh-in")
        val bodyWeightKg = latestWeighIn.weightKg

        val (records, previousExpenditureKcal) = expenditureRepository.loadRecords()

        val result = weeklyCheckIn(
            records = records,
            previousExpenditureKcal = previousExpenditureKcal,
            bodyWeightKg = bodyWeightKg,
            targetRateKgPerWeek = targetRateKgPerWeek,
            proteinGPerKg = proteinGPerKg,
            fatGPerKg = fatGPerKg,
            config = EngineConfig(),
        )

        val status = when (result.status) {
            "ready" -> "pending"
            else -> result.status // "held" already matches the schema's vocabulary
        }
        val modules = buildJsonArray {
            result.modules.forEach { module ->
                add(buildJsonObject {
                    put("key", module.key)
                    put("action", module.action)
                })
            }
        }
        val payload = NewCheckIn(
            userId = userId,
            weekStart = weekStart.toString(),
            weekEnd = weekEnd.toString(),
            status = status,
            previousExpenditureKcal = previousExpenditureKcal,
            observedExpenditureKcal = result.estimate.estimateKcal,
            proposedExpenditureKcal = result.targets?.calories,
            proposedCalories = result.targets?.calories,
            proposedProteinG = result.targets?.proteinG,
            proposedCarbsG = result.targets?.carbsG,
            proposedFatG = result.targets?.fatG,
            modules = modules,
            explanation = result.explanation,
        )
        client.postgrest.from("weekly_check_ins").upsert(payload) { select() }.decodeSingle<PersistedCheckIn>()

        return result
    }

    override suspend fun resolve(weekStart: LocalDate, accepted: Boolean): PersistedCheckIn {
        val userId = requireUserId()
        val existing = getCheckIn(weekStart)
            ?: error("No check-in found for week_start=$weekStart")
        require(existing.status == "pending") {
            "Only a pending check-in can be resolved, got status='${existing.status}' for week_start=$weekStart"
        }
        return client.postgrest.from("weekly_check_ins").update({
            set("status", if (accepted) "accepted" else "declined")
            set("resolved_at", Instant.now().toString())
        }) {
            filter {
                eq("user_id", userId)
                eq("week_start", weekStart.toString())
            }
            select()
        }.decodeSingle<PersistedCheckIn>()
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compiles cleanly (or, if the Android SDK is unavailable in this sandbox, say so explicitly and rely on the manual API-surface review below instead of claiming this ran).

Manual verification performed in place of a live compile (record in the task report): `upsert(payload) { select() }.decodeSingle<PersistedCheckIn>()` matches the already-verified pattern in `TrendRepository.recomputeTrend`/`DayStatusRepository.setStatus`, using the schema's `unique (user_id, week_start)` as the implicit conflict target (the composite unique constraint, not the `id` primary key — same situation as `weight_trend_points`' composite primary key). `client.postgrest.from(table).update({ set(...) ; set(...) }) { filter { ... }; select() }` matches the already-verified pattern in `LogRepository.deleteEntry`'s sibling `update` usage (that one sets a single column; this one sets two — both via repeated `set(...)` calls inside the same update-body lambda, which is a different parameter position from the `filter{}` block's own same-column-collapse hazard and does not trigger it, since `set` targets distinct columns `status` and `resolved_at`, not two conditions on one column).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/CheckInRepository.kt
git commit -m "feat: add CheckInRepository computing and persisting weekly_check_ins from full logged history"
```

---

### Task 4: AppContainer wiring and gaps documentation

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`
- Create: `docs/WEEKLY_CHECKIN_GAPS.md`

**Interfaces:**
- Consumes: `CheckInRepository`/`SupabaseCheckInRepository` from Task 3.
- Produces: `AppContainer.checkInRepository: CheckInRepository`.

- [ ] **Step 1: Add the wiring**

In `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`, add one line after `expenditureRepository`:

```kotlin
    val checkInRepository: CheckInRepository by lazy {
        SupabaseCheckInRepository(client, expenditureRepository, weightRepository)
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
    val checkInRepository: CheckInRepository by lazy {
        SupabaseCheckInRepository(client, expenditureRepository, weightRepository)
    }
}
```

- [ ] **Step 2: Write the gaps documentation**

Create `docs/WEEKLY_CHECKIN_GAPS.md`:

```markdown
# Weekly check-in — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From building
`docs/superpowers/plans/2026-08-03-weekly-check-in.md` (branch
`claude/macro-factor-app-dev-6twv5o`), which resolved
`docs/ADAPTIVE_ENGINE_GAPS.md`'s "weeklyCheckIn's 'ready' status has no home
in weekly_check_ins.status" gap.

## Resolution: status vocabulary mapping

`CheckInResult.status` ("held"|"ready") is mapped explicitly in
`CheckInRepository.recomputeCheckIn`: `"ready"` becomes `"pending"`
(awaiting the user's accept/decline via `resolve`), `"held"` is passed
through unchanged (it already matches the schema's CHECK constraint).

## Still open: no real source for `targetRateKgPerWeek`

`CheckInRepository.recomputeCheckIn` requires the caller to supply
`targetRateKgPerWeek` directly -- there is no `macro_programs` read path in
this codebase yet (that table, and the whole coached/collaborative/manual
program concept, is out of scope for every slice on this branch so far).
`program_id` is always `null` on every persisted row for the same reason.
Whoever builds a `macro_programs` slice or the UI layer that lets a user set
a goal rate owns wiring a real source in; nothing here invents a default
rate to paper over the gap.

## Still open: `recomputeCheckIn` requires at least one weigh-in

If `WeightRepository.listEntries(Instant.EPOCH)` returns no rows at all,
`recomputeCheckIn` throws `IllegalStateException` rather than inventing a
placeholder body weight (macro targets are computed directly from it, so a
fabricated number would silently misrepresent the user's real macros). A
future UI must guard against offering a check-in before the user has logged
at least one weigh-in.

## Still open: no scheduling/trigger for `recomputeCheckIn`

Like `TrendRepository.recomputeTrend`/`ExpenditureRepository.recomputeExpenditure`,
`recomputeCheckIn` is entirely caller-driven -- nothing in this slice calls
it, nothing decides which `weekStart`/`weekEnd` to pass. Whoever wires up
the first caller needs to decide the week boundary convention (calendar
week? rolling 7 days since the user's last check-in?) and when
recomputation happens.

## Still open: no test exercises `recomputeCheckIn`/`resolve` end-to-end

Like `TrendRepository`/`ExpenditureRepository`, `CheckInRepository` has no
dedicated unit test -- there is no mock/fake Postgrest client in this
codebase, and no live Supabase project exists in this sandbox. The
status-mapping and precondition logic are covered by manual review and by
the already-tested pure functions it composes (`weeklyCheckIn`,
`AdaptiveEngine.estimateExpenditure`), but the wiring between them is not
independently tested.
```

- [ ] **Step 3: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all existing tests plus `CheckInModelsTest` green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/AppContainer.kt docs/WEEKLY_CHECKIN_GAPS.md
git commit -m "feat: wire CheckInRepository into AppContainer; document weekly-check-in gaps"
```

---

## Self-Review

**1. Spec coverage:** `ExpenditureRepository.loadRecords()` refactor, pure, external behavior unchanged (Task 1) ✅. `CheckInModuleDto`/`PersistedCheckIn`/`NewCheckIn` models with the encodeDefaults-safe test (Task 2) ✅. `CheckInRepository` implementing all 6 product decisions — status mapping, upsert-by-`(user_id, week_start)`, `loadRecords()` reuse for both records and anchor, real body-weight source with a loud precondition failure, `resolve`'s pending-only guard (Task 3) ✅. `AppContainer` wiring and `docs/WEEKLY_CHECKIN_GAPS.md` (Task 4) ✅. UI/`macro_programs`/`program_update` explicitly excluded ✅.

**2. Placeholder scan:** No TBD/TODO; all code blocks are complete; no "similar to Task N" references — none found.

**3. Type consistency:** `ExpenditureRepository.loadRecords(): Pair<List<DailyRecord>, Double?>` (Task 1) matches Task 3's destructuring (`val (records, previousExpenditureKcal) = expenditureRepository.loadRecords()`) and the exact parameter names/types `weeklyCheckIn(records: List<DailyRecord>, previousExpenditureKcal: Double?, ...)` already expects. `CheckInResult`/`CheckInModule` (pre-existing, `WeeklyCheckIn.kt`) field names (`status`, `estimate`, `modules`, `targets`, `explanation`; `key`, `action`) match Task 3's usage exactly (`result.status`, `result.modules`, `module.key`/`module.action`, `result.targets?.calories`/`proteinG`/`carbsG`/`fatG`). `NewCheckIn`'s field names (Task 2) match every value Task 3 constructs it with. `AppContainer.checkInRepository`'s declared type and constructor call (Task 4) match Task 3's produced interface/impl names and constructor signature (`client, expenditureRepository, weightRepository`) exactly.
