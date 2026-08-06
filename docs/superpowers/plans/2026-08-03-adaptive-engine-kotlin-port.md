# Adaptive Engine Kotlin Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `adaptive_engine.py` to Kotlin as a pure, zero-I/O domain module, with Kotlin tests that assert the exact numeric fixtures captured from running the real Python reference this session — not re-derived, not approximated.

**Architecture:** Four files mirroring the Python module's own internal structure (models → core estimation engine → target-setting math → weekly check-in orchestration), each a straightforward line-by-line translation. No new abstractions beyond what Python already has; this is a port, not a redesign.

**Tech Stack:** Kotlin, `java.time.LocalDate`/`ChronoUnit` (native on minSdk 26), `java.math.BigDecimal` (for Python-compatible rounding), JUnit 4.

## Global Constraints

- This is a **pure port with zero I/O** — no Supabase, no repositories, no UI. `adaptive_engine.py` itself has none; the Kotlin port must not add any.
- Every numeric test assertion in this plan is copied from an actual run of the Python reference captured this session (either the existing `tests/test_adaptive_engine.py` fixtures, ad-hoc reproductions of the same functions, or `examples/checkin.json` run through `python3 adaptive_engine.py`) — never invented, never hand-approximated.
- **Rounding must match Python's `round(x, n)` exactly**: Python rounds half-to-even on the float's exact IEEE754 binary value. In Kotlin, use `java.math.BigDecimal(value)` — the exact-binary-value constructor, **not** `BigDecimal.valueOf(value)`, which goes through `Double.toString()` first and would round differently — with `.setScale(n, RoundingMode.HALF_EVEN).toDouble()`. This reproduces Python's rounding quirks too (e.g. `round(2.675, 2) == 2.67`), because both languages end up rounding the identical underlying double.
- A declared fast is countable only when the caller explicitly stores zero calories — `nutritionIsCountable` must preserve this exactly (already the existing rule from the daily-logger slice; this port is what will eventually consume it).
- The `EngineConfig`/macro-default values (7700 kcal/kg, 0.20 EWMA alpha, 100 kcal damping cap, Mifflin-St Jeor/Katch-McArdle BMR, 1.8/0.8 g/kg protein/fat defaults) are this repository's explicit product parameters, not validated reconstructions of any third-party app's private values — see `docs/ADAPTIVE_ENGINE_CONTRACT.md`.
- The existing `app/src/main/java/com/macroplus/app/domain/AdaptiveNutrition.kt` and its test are an old partial stub predating this port (missing coverage gating, holding states, confidence tiers, the `DailyRecord`/weekly-check-in structure entirely). **Delete both** as part of Task 1 — they are superseded, not supplemented.
- minSdk is 26 — `java.time.LocalDate`/`ChronoUnit` are usable directly, no desugaring needed.

---

## File Structure

```
app/src/main/java/com/macroplus/app/domain/
  AdaptiveNutrition.kt           # DELETE (old stub, superseded)
  Rounding.kt                    # NEW — round1/round4, Python-compatible rounding
  AdaptiveEngineModels.kt        # NEW — EngineConfig, DailyRecord, ExpenditureEstimate
  AdaptiveEngine.kt              # NEW — nutritionIsCountable, weightTrend, estimateExpenditure
  MacroTargeting.kt              # NEW — MacroTargets, initialExpenditureKcal, calorieTarget, macroTargets
  WeeklyCheckIn.kt               # NEW — CheckInModule, CheckInResult, weeklyCheckIn
app/src/test/java/com/macroplus/app/domain/
  AdaptiveNutritionTest.kt       # DELETE (old stub test, superseded)
  RoundingTest.kt                # NEW
  AdaptiveEngineModelsTest.kt    # NEW
  AdaptiveEngineTest.kt          # NEW
  MacroTargetingTest.kt          # NEW
  WeeklyCheckInTest.kt           # NEW
```

---

### Task 1: Rounding + models, delete the old stub

**Files:**
- Delete: `app/src/main/java/com/macroplus/app/domain/AdaptiveNutrition.kt`
- Delete: `app/src/test/java/com/macroplus/app/domain/AdaptiveNutritionTest.kt`
- Create: `app/src/main/java/com/macroplus/app/domain/Rounding.kt`
- Create: `app/src/main/java/com/macroplus/app/domain/AdaptiveEngineModels.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/RoundingTest.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/AdaptiveEngineModelsTest.kt`

**Interfaces:**
- Consumes: nothing (leaf task)
- Produces: `round1(value: Double): Double`, `round4(value: Double): Double` (internal, package-visible); `EngineConfig` (11 fields, all with Python-matching defaults), `DailyRecord(day: LocalDate, calories: Double? = null, weightKg: Double? = null, nutritionStatus: String = "complete")`, `ExpenditureEstimate` (11 fields). All later tasks depend on these three types and the two rounding functions.

- [ ] **Step 1: Delete the old stub files**

```bash
git rm app/src/main/java/com/macroplus/app/domain/AdaptiveNutrition.kt
git rm app/src/test/java/com/macroplus/app/domain/AdaptiveNutritionTest.kt
```

- [ ] **Step 2: Write the failing rounding test**

```kotlin
package com.macroplus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RoundingTest {

    @Test
    fun matchesPythonsRoundHalfToEvenTieCase() {
        // Python: round(0.25, 1) == 0.2 -- 0.25 is an exact tie (0.25 is exactly
        // representable in binary), so it rounds to the even neighbor, 0.2.
        assertEquals(0.2, round1(0.25), 0.0)
    }

    @Test
    fun matchesPythonsFloatRepresentationQuirk() {
        // Python: round(0.15, 1) == 0.1 -- 0.15 is NOT exactly representable in
        // binary; the stored double is slightly below the true midpoint, so
        // this isn't really a tie at all once you look at the actual bits.
        assertEquals(0.1, round1(0.15), 0.0)
    }

    @Test
    fun round4MatchesAPythonFixtureValue() {
        // Python: round(-0.14445567999998943, 4) == -0.1445
        assertEquals(-0.1445, round4(-0.14445567999998943), 0.0)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.RoundingTest"`
Expected: FAIL — `round1`/`round4` unresolved.

- [ ] **Step 4: Write Rounding.kt**

```kotlin
package com.macroplus.app.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rounds like Python's round(x, n): round-half-to-even on the double's exact
 * binary value. BigDecimal(value) (NOT BigDecimal.valueOf, which goes through
 * Double.toString() first) captures that exact binary value, so this
 * reproduces Python's float-rounding quirks too -- e.g. round(2.675, 2) ==
 * 2.67 in both languages, because 2.675 is actually stored as
 * 2.67499999999999982236431605997495353221893310546875 and isn't a real tie.
 */
internal fun round1(value: Double): Double = BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toDouble()

internal fun round4(value: Double): Double = BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN).toDouble()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.RoundingTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: Write the failing models test**

```kotlin
package com.macroplus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveEngineModelsTest {
    @Test
    fun engineConfigDefaultsMatchThePythonReferenceExactly() {
        // adaptive_engine.py's EngineConfig dataclass defaults, copied verbatim.
        val config = EngineConfig()
        assertEquals(7700.0, config.kcalPerKg, 0.0)
        assertEquals(0.20, config.trendAlpha, 0.0)
        assertEquals(14, config.minimumHistoryDays)
        assertEquals(7, config.coverageWindowDays)
        assertEquals(6, config.minimumNutritionDaysPerWeek)
        assertEquals(1, config.minimumWeightDaysPerWeek)
        assertEquals(100.0, config.maximumExpenditureStepKcal, 0.0)
        assertEquals(1000.0, config.minimumExpenditureKcal, 0.0)
        assertEquals(6000.0, config.maximumExpenditureKcal, 0.0)
        assertEquals(1.8, config.defaultProteinGPerKg, 0.0)
        assertEquals(0.8, config.defaultFatGPerKg, 0.0)
    }

    @Test
    fun dailyRecordDefaultsToACompleteStatusWithNoValues() {
        val record = DailyRecord(day = java.time.LocalDate.of(2026, 7, 1))
        assertEquals("complete", record.nutritionStatus)
        assertEquals(null, record.calories)
        assertEquals(null, record.weightKg)
    }
}
```

- [ ] **Step 7: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.AdaptiveEngineModelsTest"`
Expected: FAIL — `EngineConfig`/`DailyRecord` unresolved.

- [ ] **Step 8: Write AdaptiveEngineModels.kt**

```kotlin
package com.macroplus.app.domain

import java.time.LocalDate

/**
 * Product parameters for the adaptive engine -- mirrors adaptive_engine.py's
 * EngineConfig dataclass field-for-field, including its defaults. These are
 * this repository's explicit product choices, not validated reconstructions
 * of any third-party app's private parameters (see
 * docs/ADAPTIVE_ENGINE_CONTRACT.md). Keep them versioned and test-covered
 * before changing any of them.
 */
data class EngineConfig(
    val kcalPerKg: Double = 7700.0,
    val trendAlpha: Double = 0.20,
    val minimumHistoryDays: Int = 14,
    val coverageWindowDays: Int = 7,
    val minimumNutritionDaysPerWeek: Int = 6,
    val minimumWeightDaysPerWeek: Int = 1,
    val maximumExpenditureStepKcal: Double = 100.0,
    val minimumExpenditureKcal: Double = 1000.0,
    val maximumExpenditureKcal: Double = 6000.0,
    val defaultProteinGPerKg: Double = 1.8,
    val defaultFatGPerKg: Double = 0.8,
)

/**
 * One calendar day of input to the engine. `nutritionStatus` is one of
 * "complete", "partial", "fasted", "unlogged" -- see
 * AdaptiveEngine.nutritionIsCountable for what each means.
 */
data class DailyRecord(
    val day: LocalDate,
    val calories: Double? = null,
    val weightKg: Double? = null,
    val nutritionStatus: String = "complete",
)

/**
 * Mirrors adaptive_engine.py's ExpenditureEstimate dataclass. windowStart/
 * windowEnd are ISO date strings -- LocalDate.toString() already produces
 * yyyy-MM-dd, matching Python's date.isoformat().
 */
data class ExpenditureEstimate(
    val state: String,
    val confidence: String,
    val estimateKcal: Double?,
    val rawEstimateKcal: Double?,
    val previousEstimateKcal: Double?,
    val trendSlopeKgPerWeek: Double?,
    val nutritionDays: Int,
    val weightDays: Int,
    val windowStart: String?,
    val windowEnd: String?,
    val explanation: String,
)
```

- [ ] **Step 9: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.AdaptiveEngineModelsTest"`
Expected: PASS (2 tests)

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/macroplus/app/domain/Rounding.kt \
        app/src/main/java/com/macroplus/app/domain/AdaptiveEngineModels.kt \
        app/src/test/java/com/macroplus/app/domain/RoundingTest.kt \
        app/src/test/java/com/macroplus/app/domain/AdaptiveEngineModelsTest.kt
git commit -m "feat: add adaptive engine models and Python-compatible rounding, remove old stub"
```

(The `git rm` from Step 1 stages the deletions; they'll be included in this same commit.)

---

### Task 2: AdaptiveEngine (nutrition countability, weight trend, expenditure estimation)

**Files:**
- Create: `app/src/main/java/com/macroplus/app/domain/AdaptiveEngine.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/AdaptiveEngineTest.kt`

**Interfaces:**
- Consumes: `EngineConfig`, `DailyRecord`, `ExpenditureEstimate` (Task 1), `round1`/`round4` (Task 1)
- Produces: `AdaptiveEngine.nutritionIsCountable(record: DailyRecord): Boolean`, `AdaptiveEngine.weightTrend(values: List<Double?>, alpha: Double = 0.20): List<Double?>`, `AdaptiveEngine.estimateExpenditure(records: List<DailyRecord>, previousEstimateKcal: Double? = null, config: EngineConfig = EngineConfig()): ExpenditureEstimate`. Task 4's `weeklyCheckIn` calls `estimateExpenditure`.

**A deliberate simplification vs. the Python source, noted so a reviewer doesn't flag it as a bug:** Python's `_coverage_explanation` returns a 4-tuple `(enough, reasons, gate_nutrition, gate_weight)`, but `estimate_expenditure` only ever uses the first two (`gate_nutrition`/`gate_weight` are assigned and never read again in the Python source). This port's private `coverageExplanation` only returns `(enough, reasons)` -- this is not a behavior change, just not porting dead code.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macroplus.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveEngineTest {

    @Test
    fun nutritionIsCountableForACompleteDayWithNonNegativeCalories() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = 2200.0, nutritionStatus = "complete")
        assertTrue(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun nutritionIsNotCountableForACompleteDayWithNoCaloriesRecorded() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = null, nutritionStatus = "complete")
        assertFalse(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun nutritionIsNotCountableForAPartialDay() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = 1200.0, nutritionStatus = "partial")
        assertFalse(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun nutritionIsNotCountableForAnUnloggedDay() {
        val record = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = null, nutritionStatus = "unlogged")
        assertFalse(AdaptiveEngine.nutritionIsCountable(record))
    }

    @Test
    fun aDeclaredFastIsCountableOnlyWithExplicitZeroCalories() {
        val declaredZero = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = 0.0, nutritionStatus = "fasted")
        val declaredWithoutZero = DailyRecord(day = LocalDate.of(2026, 7, 1), calories = null, nutritionStatus = "fasted")
        assertTrue(AdaptiveEngine.nutritionIsCountable(declaredZero))
        assertFalse(AdaptiveEngine.nutritionIsCountable(declaredWithoutZero))
    }

    @Test
    fun weightTrendSmoothsASpikeAcrossFiveDays() {
        // adaptive_engine.py: weight_trend([90.0, 90.2, 90.1, 95.0, 90.2], alpha=0.2)
        val trend = AdaptiveEngine.weightTrend(listOf(90.0, 90.2, 90.1, 95.0, 90.2), alpha = 0.2)
        assertEquals(5, trend.size)
        assertEquals(90.0, trend[0]!!, 0.0001)
        assertEquals(90.04, trend[1]!!, 0.0001)
        assertEquals(90.052, trend[2]!!, 0.0001)
        assertEquals(91.0416, trend[3]!!, 0.0001)
        assertEquals(90.87328000000001, trend[4]!!, 0.00000001)
    }

    private fun fullCoverageRecords(count: Int = 14): List<DailyRecord> {
        val start = LocalDate.of(2026, 7, 1)
        return (0 until count).map { index ->
            DailyRecord(day = start.plusDays(index.toLong()), calories = 2800.0, weightKg = 90 - index * 0.04)
        }
    }

    @Test
    fun estimateExpenditureUpdatesWithFullCoverage() {
        // adaptive_engine.py test_expenditure_updates_only_with_coverage (updating branch)
        val estimate = AdaptiveEngine.estimateExpenditure(fullCoverageRecords(), previousEstimateKcal = 2800.0)

        assertEquals("updating", estimate.state)
        assertEquals("medium", estimate.confidence)
        assertEquals(2900.0, estimate.estimateKcal!!, 0.0001)
        assertEquals(3026.6, estimate.rawEstimateKcal!!, 0.0001)
        assertEquals(2800.0, estimate.previousEstimateKcal!!, 0.0001)
        assertEquals(-0.206, estimate.trendSlopeKgPerWeek!!, 0.0001)
        assertEquals(14, estimate.nutritionDays)
        assertEquals(14, estimate.weightDays)
        assertEquals("2026-07-01", estimate.windowStart)
        assertEquals("2026-07-14", estimate.windowEnd)
        assertEquals("Expenditure updated from logged intake and smoothed weight trend.", estimate.explanation)
    }

    @Test
    fun estimateExpenditureHoldsWhenTwoDaysAreOnlyPartiallyLogged() {
        // adaptive_engine.py test_expenditure_updates_only_with_coverage (holding branch)
        val records = fullCoverageRecords().toMutableList()
        records[2] = records[2].copy(calories = null, nutritionStatus = "partial")
        records[3] = records[3].copy(calories = null, nutritionStatus = "partial")

        val held = AdaptiveEngine.estimateExpenditure(records, previousEstimateKcal = 2800.0)

        assertEquals("holding", held.state)
        assertEquals("low", held.confidence)
        assertEquals(2800.0, held.estimateKcal!!, 0.0001)
        assertNull(held.rawEstimateKcal)
        assertEquals(2800.0, held.previousEstimateKcal!!, 0.0001)
        // Unrounded -- the holding branch never applies round4, unlike the updating branch.
        assertEquals(-0.20601168372240294, held.trendSlopeKgPerWeek!!, 0.00000000001)
        assertEquals(12, held.nutritionDays)
        assertEquals(14, held.weightDays)
        assertEquals("2026-07-01", held.windowStart)
        assertEquals("2026-07-14", held.windowEnd)
        assertEquals("Nutrition logging is below the 6-of-7-day update gate.", held.explanation)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.AdaptiveEngineTest"`
Expected: FAIL -- `AdaptiveEngine` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macroplus.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Deterministic reference engine -- a faithful Kotlin port of
 * adaptive_engine.py. Uses logged intake and smoothed weight change;
 * requires enough coverage; holds when data are inadequate; damps updates.
 * No wearable calorie estimates are used. A missing nutrition day is never
 * silently interpolated; a missing weight day may be tolerated by the trend
 * filter because the scale is a noisy measurement. See
 * docs/ADAPTIVE_ENGINE_CONTRACT.md.
 */
object AdaptiveEngine {

    /**
     * A declared fast is countable only when the caller explicitly stores
     * zero calories. An unlogged day remains unknown.
     */
    fun nutritionIsCountable(record: DailyRecord): Boolean {
        if (record.nutritionStatus == "fasted") {
            return record.calories == 0.0
        }
        return record.nutritionStatus == "complete" && record.calories != null && record.calories >= 0
    }

    /** Recent-weight-weighted EWMA, carrying the last trend across gaps. */
    fun weightTrend(values: List<Double?>, alpha: Double = 0.20): List<Double?> {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be greater than 0 and no greater than 1" }
        var previous: Double? = null
        return values.map { value ->
            if (value != null) {
                previous = previous?.let { alpha * value + (1 - alpha) * it } ?: value
            }
            previous
        }
    }

    private fun linearSlope(points: List<Pair<LocalDate, Double>>): Double? {
        if (points.size < 2) return null
        val origin = points[0].first
        val xs = points.map { (day, _) -> ChronoUnit.DAYS.between(origin, day).toDouble() }
        val ys = points.map { it.second }
        val xMean = xs.average()
        val yMean = ys.average()
        val denominator = xs.sumOf { (it - xMean) * (it - xMean) }
        if (denominator == 0.0) return null
        val numerator = xs.indices.sumOf { i -> (xs[i] - xMean) * (ys[i] - yMean) }
        return numerator / denominator
    }

    private fun periodCoverage(records: List<DailyRecord>, start: LocalDate, end: LocalDate): Pair<Int, Int> {
        val period = records.filter { it.day >= start && it.day <= end }
        val nutritionDays = period.count { nutritionIsCountable(it) }
        val weightDays = period.count { it.weightKg != null }
        return nutritionDays to weightDays
    }

    private class CoverageExplanation(val enough: Boolean, val reasons: List<String>)

    private fun coverageExplanation(records: List<DailyRecord>, config: EngineConfig): CoverageExplanation {
        if (records.isEmpty()) {
            return CoverageExplanation(false, listOf("No daily records are available."))
        }
        val first = records.first().day
        val last = records.last().day
        if (ChronoUnit.DAYS.between(first, last) + 1 < config.minimumHistoryDays) {
            return CoverageExplanation(false, listOf("More history is required before updating expenditure."))
        }
        val lastWeekStart = last.minusDays((config.coverageWindowDays - 1).toLong())
        val previousWeekEnd = lastWeekStart.minusDays(1)
        val previousWeekStart = previousWeekEnd.minusDays((config.coverageWindowDays - 1).toLong())
        val (currentNutrition, currentWeight) = periodCoverage(records, lastWeekStart, last)
        val (previousNutrition, previousWeight) = periodCoverage(records, previousWeekStart, previousWeekEnd)
        val reasons = mutableListOf<String>()
        if (currentNutrition < config.minimumNutritionDaysPerWeek || previousNutrition < config.minimumNutritionDaysPerWeek) {
            reasons.add("Nutrition logging is below the 6-of-7-day update gate.")
        }
        if (currentWeight < config.minimumWeightDaysPerWeek || previousWeight < config.minimumWeightDaysPerWeek) {
            reasons.add("At least one weigh-in is required in each seven-day period.")
        }
        return CoverageExplanation(reasons.isEmpty(), reasons)
    }

    /** Estimates TDEE as mean logged intake minus energy represented by trend slope. */
    fun estimateExpenditure(
        records: List<DailyRecord>,
        previousEstimateKcal: Double? = null,
        config: EngineConfig = EngineConfig(),
    ): ExpenditureEstimate {
        val ordered = records.sortedBy { it.day }
        if (ordered.isEmpty()) {
            return ExpenditureEstimate(
                state = "holding", confidence = "holding", estimateKcal = previousEstimateKcal,
                rawEstimateKcal = null, previousEstimateKcal = previousEstimateKcal,
                trendSlopeKgPerWeek = null, nutritionDays = 0, weightDays = 0,
                windowStart = null, windowEnd = null, explanation = "No records are available.",
            )
        }
        val coverage = coverageExplanation(ordered, config)
        val end = ordered.last().day
        val start = maxOf(ordered.first().day, end.minusDays((config.coverageWindowDays * 2 - 1).toLong()))
        val window = ordered.filter { it.day >= start && it.day <= end }
        val countable = window.filter { nutritionIsCountable(it) }
        val nutritionDays = countable.size
        val weightDays = window.count { it.weightKg != null }
        val trendValues = weightTrend(ordered.map { it.weightKg }, config.trendAlpha)
        val trendPoints = ordered.zip(trendValues)
            .filter { (record, trend) -> record.day >= start && record.day <= end && trend != null }
            .map { (record, trend) -> record.day to trend!! }
        val slopePerDay = linearSlope(trendPoints)
        val slopePerWeek = slopePerDay?.times(7.0)
        var raw: Double? = null
        if (coverage.enough && countable.isNotEmpty() && slopePerDay != null) {
            val meanCalories = countable.mapNotNull { it.calories }.average()
            val rawValue = meanCalories - slopePerDay * config.kcalPerKg
            raw = rawValue.coerceIn(config.minimumExpenditureKcal, config.maximumExpenditureKcal)
        }
        if (raw == null) {
            val confidence = if (previousEstimateKcal == null) "holding" else "low"
            val explanation = if (coverage.reasons.isNotEmpty()) {
                coverage.reasons.joinToString(" ")
            } else {
                "A trend slope and complete intake coverage are required."
            }
            return ExpenditureEstimate(
                state = "holding", confidence = confidence, estimateKcal = previousEstimateKcal,
                rawEstimateKcal = null, previousEstimateKcal = previousEstimateKcal,
                trendSlopeKgPerWeek = slopePerWeek, nutritionDays = nutritionDays,
                weightDays = weightDays, windowStart = start.toString(), windowEnd = end.toString(),
                explanation = explanation,
            )
        }
        val estimate = if (previousEstimateKcal == null) {
            raw
        } else {
            previousEstimateKcal + (raw - previousEstimateKcal).coerceIn(
                -config.maximumExpenditureStepKcal,
                config.maximumExpenditureStepKcal,
            )
        }
        val spanDays = ChronoUnit.DAYS.between(ordered.first().day, end) + 1
        val confidence = when {
            spanDays >= 28 && nutritionDays >= 12 && weightDays >= 4 -> "high"
            spanDays >= 14 -> "medium"
            else -> "low"
        }
        return ExpenditureEstimate(
            state = "updating", confidence = confidence, estimateKcal = round1(estimate),
            rawEstimateKcal = round1(raw), previousEstimateKcal = previousEstimateKcal,
            trendSlopeKgPerWeek = slopePerWeek?.let { round4(it) },
            nutritionDays = nutritionDays, weightDays = weightDays,
            windowStart = start.toString(), windowEnd = end.toString(),
            explanation = "Expenditure updated from logged intake and smoothed weight trend.",
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.AdaptiveEngineTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macroplus/app/domain/AdaptiveEngine.kt app/src/test/java/com/macroplus/app/domain/AdaptiveEngineTest.kt
git commit -m "feat: port estimate_expenditure and its coverage gating to Kotlin"
```

---

### Task 3: MacroTargeting (starting estimate, calorie target, macro split)

**Files:**
- Create: `app/src/main/java/com/macroplus/app/domain/MacroTargeting.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/MacroTargetingTest.kt`

**Interfaces:**
- Consumes: `EngineConfig` (Task 1), `round1` (Task 1)
- Produces: `MacroTargets(calories, proteinG, carbsG, fatG, macroCalories)`, `initialExpenditureKcal(weightKg, heightCm, ageYears, sex = "unspecified", activityLevel = "moderate", bodyFatPct: Double? = null): Double`, `calorieTarget(expenditureKcal, targetRateKgPerWeek, config = EngineConfig()): Double`, `macroTargets(calories, bodyWeightKg, proteinGPerKg = EngineConfig().defaultProteinGPerKg, fatGPerKg = EngineConfig().defaultFatGPerKg): MacroTargets`. Task 4's `weeklyCheckIn` calls `calorieTarget` and `macroTargets`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macroplus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroTargetingTest {

    @Test
    fun calorieTargetIsSignedByGoalRate() {
        // adaptive_engine.py: calorie_target(2800, -0.5) == 2250.0; calorie_target(2800, 0.5) == 3350.0
        assertEquals(2250.0, calorieTarget(2800.0, -0.5), 0.0001)
        assertEquals(3350.0, calorieTarget(2800.0, 0.5), 0.0001)
    }

    @Test
    fun macroTargetsAllocateFromExplicitPreferences() {
        // adaptive_engine.py: macro_targets(2500, 90, protein_g_per_kg=2.0, fat_g_per_kg=0.8)
        val targets = macroTargets(2500.0, 90.0, proteinGPerKg = 2.0, fatGPerKg = 0.8)
        assertEquals(2500.0, targets.calories, 0.0001)
        assertEquals(180.0, targets.proteinG, 0.0001)
        assertEquals(283.0, targets.carbsG, 0.0001)
        assertEquals(72.0, targets.fatG, 0.0001)
        assertEquals(2500.0, targets.macroCalories, 0.0001)
        assertTrue(targets.carbsG >= 0.0)
    }

    @Test
    fun initialExpenditureUsesMifflinStJeorForMales() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=90, height_cm=177, age_years=32, sex="male", activity_level="moderate") == 2869.4
        val estimate = initialExpenditureKcal(weightKg = 90.0, heightCm = 177.0, ageYears = 32.0, sex = "male", activityLevel = "moderate")
        assertEquals(2869.4, estimate, 0.0001)
    }

    @Test
    fun initialExpenditureUsesMifflinStJeorForFemales() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=65, height_cm=165, age_years=28, sex="female", activity_level="light") == 1897.8
        val estimate = initialExpenditureKcal(weightKg = 65.0, heightCm = 165.0, ageYears = 28.0, sex = "female", activityLevel = "light")
        assertEquals(1897.8, estimate, 0.0001)
    }

    @Test
    fun initialExpenditureUsesKatchMcArdleWhenBodyFatIsGiven() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=80, height_cm=178, age_years=30, sex="male", activity_level="high", body_fat_pct=18) == 3082.5
        val estimate = initialExpenditureKcal(weightKg = 80.0, heightCm = 178.0, ageYears = 30.0, sex = "male", activityLevel = "high", bodyFatPct = 18.0)
        assertEquals(3082.5, estimate, 0.0001)
    }

    @Test
    fun initialExpenditureUsesAMidpointFallbackForUnspecifiedSex() {
        // adaptive_engine.py: initial_expenditure_kcal(weight_kg=70, height_cm=170, age_years=25, sex="unspecified", activity_level="sedentary") == 1871.4
        val estimate = initialExpenditureKcal(weightKg = 70.0, heightCm = 170.0, ageYears = 25.0, sex = "unspecified", activityLevel = "sedentary")
        assertEquals(1871.4, estimate, 0.0001)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.MacroTargetingTest"`
Expected: FAIL -- `calorieTarget`/`macroTargets`/`initialExpenditureKcal` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macroplus.app.domain

/** Mirrors adaptive_engine.py's macro_targets() return dict. */
data class MacroTargets(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val macroCalories: Double,
)

/**
 * Returns a transparent starting estimate; observed data
 * (AdaptiveEngine.estimateExpenditure) should replace it as soon as coverage
 * allows.
 */
fun initialExpenditureKcal(
    weightKg: Double,
    heightCm: Double,
    ageYears: Double,
    sex: String = "unspecified",
    activityLevel: String = "moderate",
    bodyFatPct: Double? = null,
): Double {
    val bmr = if (bodyFatPct != null && bodyFatPct in 2.0..70.0) {
        val leanMass = weightKg * (1 - bodyFatPct / 100)
        370 + 21.6 * leanMass
    } else {
        when (sex) {
            "male" -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears + 5
            "female" -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears - 161
            // Midpoint fallback rather than guessing a sex-specific equation.
            else -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears - 78
        }
    }
    val factors = mapOf(
        "sedentary" to 1.20,
        "light" to 1.375,
        "moderate" to 1.55,
        "high" to 1.725,
        "very_high" to 1.90,
    )
    val factor = factors[activityLevel] ?: factors.getValue("moderate")
    return round1(maxOf(1000.0, bmr * factor))
}

/** Signed rate: negative loses weight, positive gains weight, zero maintains. */
fun calorieTarget(
    expenditureKcal: Double,
    targetRateKgPerWeek: Double,
    config: EngineConfig = EngineConfig(),
): Double = round1(expenditureKcal + targetRateKgPerWeek * config.kcalPerKg / 7)

fun macroTargets(
    calories: Double,
    bodyWeightKg: Double,
    proteinGPerKg: Double = EngineConfig().defaultProteinGPerKg,
    fatGPerKg: Double = EngineConfig().defaultFatGPerKg,
): MacroTargets {
    val protein = maxOf(0.0, bodyWeightKg * proteinGPerKg)
    val fat = maxOf(0.0, bodyWeightKg * fatGPerKg)
    val remaining = calories - protein * 4 - fat * 9
    val carbs = maxOf(0.0, remaining / 4)
    return MacroTargets(
        calories = round1(calories),
        proteinG = round1(protein),
        carbsG = round1(carbs),
        fatG = round1(fat),
        macroCalories = round1(protein * 4 + carbs * 4 + fat * 9),
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.MacroTargetingTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macroplus/app/domain/MacroTargeting.kt app/src/test/java/com/macroplus/app/domain/MacroTargetingTest.kt
git commit -m "feat: port initial_expenditure_kcal, calorie_target, and macro_targets to Kotlin"
```

---

### Task 4: WeeklyCheckIn (orchestration entry point)

**Files:**
- Create: `app/src/main/java/com/macroplus/app/domain/WeeklyCheckIn.kt`
- Test: `app/src/test/java/com/macroplus/app/domain/WeeklyCheckInTest.kt`

**Interfaces:**
- Consumes: `AdaptiveEngine.estimateExpenditure` (Task 2), `calorieTarget`/`macroTargets`/`MacroTargets` (Task 3), `EngineConfig`/`DailyRecord`/`ExpenditureEstimate` (Task 1)
- Produces: `CheckInModule(key: String, action: String)`, `CheckInResult(status, estimate, modules, targets, explanation)`, `weeklyCheckIn(records, previousExpenditureKcal, bodyWeightKg, targetRateKgPerWeek, proteinGPerKg = EngineConfig().defaultProteinGPerKg, fatGPerKg = EngineConfig().defaultFatGPerKg, config = EngineConfig()): CheckInResult`. This is the top-level entry point a future ViewModel slice will call -- nothing later in this plan consumes it further.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.macroplus.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyCheckInTest {

    @Test
    fun checkInIsHeldWithModulesWhenHistoryIsTooShort() {
        // adaptive_engine.py test_check_in_returns_hold_modules: only 7 of the
        // 14-day minimum history, all days complete/logged.
        val start = LocalDate.of(2026, 7, 1)
        val records = (0 until 7).map { index ->
            DailyRecord(day = start.plusDays(index.toLong()), calories = 2800.0, weightKg = 90 - index * 0.04)
        }

        val result = weeklyCheckIn(
            records = records,
            previousExpenditureKcal = 2800.0,
            bodyWeightKg = 90.0,
            targetRateKgPerWeek = -0.3,
        )

        assertEquals("held", result.status)
        assertNull(result.targets)
        assertEquals("holding", result.estimate.state)
        assertEquals("low", result.estimate.confidence)
        assertEquals(2800.0, result.estimate.estimateKcal!!, 0.0001)
        assertNull(result.estimate.rawEstimateKcal)
        assertEquals(-0.14445567999998943, result.estimate.trendSlopeKgPerWeek!!, 0.00000000001)
        assertEquals(7, result.estimate.nutritionDays)
        assertEquals(7, result.estimate.weightDays)
        assertEquals("2026-07-01", result.estimate.windowStart)
        assertEquals("2026-07-07", result.estimate.windowEnd)
        assertEquals("More history is required before updating expenditure.", result.estimate.explanation)

        // Exactly these two modules, in this order -- not weigh_in, since
        // weight_days (7) is not below minimum_weight_days_per_week*2 (2).
        assertEquals(2, result.modules.size)
        assertEquals(CheckInModule("partial_logging", "review incomplete nutrition days"), result.modules[0])
        assertEquals(CheckInModule("logging_break", "carry forward the last high-confidence estimate"), result.modules[1])
    }

    @Test
    fun checkInIsReadyWithTargetsForTheDocumentedExampleScenario() {
        // Ports examples/checkin.json end-to-end. Expected values captured by
        // running `python3 adaptive_engine.py --input examples/checkin.json`
        // this session.
        val records = listOf(
            DailyRecord(LocalDate.of(2026, 7, 1), calories = 2850.0, weightKg = 89.2),
            DailyRecord(LocalDate.of(2026, 7, 2), calories = 2800.0, weightKg = 89.1),
            DailyRecord(LocalDate.of(2026, 7, 3), calories = 2780.0, weightKg = 89.0),
            DailyRecord(LocalDate.of(2026, 7, 4), calories = 2820.0, weightKg = 88.9),
            DailyRecord(LocalDate.of(2026, 7, 5), calories = 2790.0, weightKg = 88.8),
            DailyRecord(LocalDate.of(2026, 7, 6), calories = 2810.0, weightKg = 88.7),
            DailyRecord(LocalDate.of(2026, 7, 7), calories = 2800.0, weightKg = 88.6),
            DailyRecord(LocalDate.of(2026, 7, 8), calories = 2770.0, weightKg = 88.5),
            DailyRecord(LocalDate.of(2026, 7, 9), calories = 2790.0, weightKg = 88.4),
            DailyRecord(LocalDate.of(2026, 7, 10), calories = 2800.0, weightKg = 88.3),
            DailyRecord(LocalDate.of(2026, 7, 11), calories = 2810.0, weightKg = 88.2),
            DailyRecord(LocalDate.of(2026, 7, 12), calories = 2780.0, weightKg = 88.1),
            DailyRecord(LocalDate.of(2026, 7, 13), calories = 2800.0, weightKg = 88.0),
            DailyRecord(LocalDate.of(2026, 7, 14), calories = 2790.0, weightKg = 87.9),
        )

        val result = weeklyCheckIn(
            records = records,
            previousExpenditureKcal = 2800.0,
            bodyWeightKg = 89.0,
            targetRateKgPerWeek = -0.3,
            proteinGPerKg = 1.8,
            fatGPerKg = 0.8,
        )

        assertEquals("ready", result.status)
        assertEquals("updating", result.estimate.state)
        assertEquals("medium", result.estimate.confidence)
        assertEquals(2900.0, result.estimate.estimateKcal!!, 0.0001)
        assertEquals(3365.8, result.estimate.rawEstimateKcal!!, 0.0001)
        assertEquals(-0.515, result.estimate.trendSlopeKgPerWeek!!, 0.0001)
        assertEquals(14, result.estimate.nutritionDays)
        assertEquals(14, result.estimate.weightDays)
        assertEquals("2026-07-01", result.estimate.windowStart)
        assertEquals("2026-07-14", result.estimate.windowEnd)

        val targets = result.targets!!
        assertEquals(2570.0, targets.calories, 0.0001)
        assertEquals(160.2, targets.proteinG, 0.0001)
        assertEquals(322.1, targets.carbsG, 0.0001)
        assertEquals(71.2, targets.fatG, 0.0001)
        assertEquals(2570.0, targets.macroCalories, 0.0001)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.WeeklyCheckInTest"`
Expected: FAIL -- `weeklyCheckIn`/`CheckInModule` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.macroplus.app.domain

/** One suggested action surfaced to the user during a held check-in. */
data class CheckInModule(val key: String, val action: String)

/** Mirrors adaptive_engine.py's weekly_check_in() return dict. */
data class CheckInResult(
    val status: String, // "held" | "ready"
    val estimate: ExpenditureEstimate,
    val modules: List<CheckInModule>,
    val targets: MacroTargets?,
    val explanation: String,
)

/**
 * Recommendations are informational, not punishment or retroactive calorie
 * debt -- see docs/ADAPTIVE_ENGINE_CONTRACT.md's check-in module contract.
 */
fun weeklyCheckIn(
    records: List<DailyRecord>,
    previousExpenditureKcal: Double?,
    bodyWeightKg: Double,
    targetRateKgPerWeek: Double,
    proteinGPerKg: Double = EngineConfig().defaultProteinGPerKg,
    fatGPerKg: Double = EngineConfig().defaultFatGPerKg,
    config: EngineConfig = EngineConfig(),
): CheckInResult {
    val estimate = AdaptiveEngine.estimateExpenditure(records, previousExpenditureKcal, config)
    if (estimate.state == "holding") {
        val modules = mutableListOf<CheckInModule>()
        if (estimate.nutritionDays < config.minimumNutritionDaysPerWeek * 2) {
            modules.add(CheckInModule("partial_logging", "review incomplete nutrition days"))
        }
        if (estimate.weightDays < config.minimumWeightDaysPerWeek * 2) {
            modules.add(CheckInModule("weigh_in", "add a weigh-in for each seven-day period"))
        }
        modules.add(CheckInModule("logging_break", "carry forward the last high-confidence estimate"))
        return CheckInResult(
            status = "held", estimate = estimate, modules = modules,
            targets = null, explanation = estimate.explanation,
        )
    }
    val estimateKcal = requireNotNull(estimate.estimateKcal) {
        "estimateExpenditure returned state=updating with a null estimateKcal -- contract violation"
    }
    val calories = calorieTarget(estimateKcal, targetRateKgPerWeek, config)
    val targets = macroTargets(calories, bodyWeightKg, proteinGPerKg, fatGPerKg)
    return CheckInResult(
        status = "ready", estimate = estimate, modules = emptyList(), targets = targets,
        explanation = "The next target uses observed expenditure and the signed goal rate; " +
            "it does not punish or average in unlogged days.",
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.macroplus.app.domain.WeeklyCheckInTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macroplus/app/domain/WeeklyCheckIn.kt app/src/test/java/com/macroplus/app/domain/WeeklyCheckInTest.kt
git commit -m "feat: port weekly_check_in to Kotlin, completing the adaptive engine port"
```

---

## Self-Review

**Spec coverage** (against CLAUDE.md's "Port the Python reference to Kotlin with matching fixture tests"):
- `EngineConfig`, `DailyRecord`, `ExpenditureEstimate` → Task 1
- `nutrition_is_countable`, `weight_trend`, `_linear_slope`, `_period_coverage`/`_coverage_explanation`, `estimate_expenditure` → Task 2
- `initial_expenditure_kcal`, `calorie_target`, `macro_targets` → Task 3
- `weekly_check_in` → Task 4
- Every function in `adaptive_engine.py` except `main()`/CLI argument parsing (out of scope -- this is a domain-layer port, not a CLI) has a corresponding Kotlin port with at least one test asserting an exact value captured from a real Python run.

**Placeholder scan:** no TODO/TBD/"add appropriate handling" strings; every code block is complete, compilable Kotlin using only stdlib + `java.time`/`java.math` (no external dependencies added).

**Type consistency:** `ExpenditureEstimate`'s field names (Task 1) match every construction site in `AdaptiveEngine.estimateExpenditure` (Task 2) and every read site in `weeklyCheckIn`/tests (Task 4). `MacroTargets`'s fields (Task 3) match `WeeklyCheckInTest`'s assertions (Task 4). `round1`/`round4` (Task 1) are called consistently by both `AdaptiveEngine.kt` (Task 2) and `MacroTargeting.kt` (Task 3) rather than each reimplementing rounding.

**Known, deliberate simplification:** `coverageExplanation`'s dropped `gate_nutrition`/`gate_weight` return values (see Task 2's note) -- verified these are genuinely unused in the Python source, not a missed requirement.

**What this plan does NOT do, on purpose:** persist any `ExpenditureEstimate`/`CheckInResult` to Supabase (the `expenditure_estimates`/`weekly_check_ins` tables already exist in the schema from the original migration, but wiring a repository is a separate later slice per `CLAUDE.md`'s order), and no Compose UI. This plan is the pure engine only, matching `adaptive_engine.py` itself having zero I/O.
