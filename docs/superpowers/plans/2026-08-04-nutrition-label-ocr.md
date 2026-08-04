# Nutrition-Label OCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user photograph a packaged food's nutrition panel and have calories/protein/carbs/fat (and, where readable, the serving size) pre-fill the Create Custom Food form, fully editable before saving.

**Architecture:** A new `NutritionLabelScannerScreen` reuses the existing CameraX capture pattern from `BarcodeScannerScreen` to take a single photo, runs on-device ML Kit Text Recognition on it, and discards the bitmap immediately. The recognized text (as line + bounding-box data) is handed to a new pure-Kotlin `NutritionLabelParser` in `domain/`, which reconstructs the label's row/column structure spatially and returns a `ParsedNutritionLabel` with each field independently nullable. The result flows back to `CreateCustomFoodScreen` via the same `NavBackStackEntry.savedStateHandle` pattern already used for the scanned barcode, and pre-fills `CreateCustomFoodViewModel`'s existing state — no new save path.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX 1.6.1 (already a dependency), ML Kit Text Recognition (new dependency), JUnit4 (existing test setup).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-04-nutrition-label-ocr-design.md` — read this first if anything below is ambiguous.
- Macros only this version — no micronutrient fields or parsing.
- On-device only — no network call for the photo or the parsing step.
- Photo is discarded immediately after OCR — never persisted, never uploaded.
- Any field the parser isn't confident about must be `null` in `ParsedNutritionLabel`, never a guessed value. The UI leaves a `null` field blank/unchanged — no visual "low confidence" indicator.
- Whole-photo failure (parser returns an all-null result) shows a retry prompt; this is the one deliberate exception to "just fall through silently."
- Energy: prefer a kcal/Cal reading if present; convert from kJ only when kcal is absent, using the same `4.184` kJ-per-kcal constant `seed_common.py`'s `KJ_PER_KCAL` already uses.
- Kotlin domain code in this repo is pure (no Android framework imports) and tested with plain JUnit — see `app/src/main/java/com/macrotrack/app/domain/ServingScaler.kt` and its test for the house style to match (KDoc explains *why*, `require()` for invariants, `object` with pure functions).

---

### Task 1: Add ML Kit Text Recognition dependency

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: the `com.google.mlkit.vision.text.*` and `com.google.mlkit.vision.text.latin.TextRecognizerOptions` classes become available to later tasks.

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, in the `dependencies {}` block, right after the existing `com.google.mlkit:barcode-scanning:17.3.0` line, add:

```kotlin
    implementation("com.google.mlkit:text-recognition:16.0.1")
```

- [ ] **Step 2: Note the version-verification caveat**

This project has twice shipped a Google/AndroidX dependency version that turned out not to match reality (`compileSdk 37`, the `org.jetbrains.kotlin.android` plugin) and only found out via a real CI build — see the branch history on `main` around commits `5c1bde9` and `1cbe371`. `16.0.1` is a plausible, but **not independently re-verified against Google's live Maven repository in this task**, version for `com.google.mlkit:text-recognition`. If the CI build in Task 2's verification step fails specifically on resolving this artifact/version, that is expected-possible and should be fixed the same way those two were: check what's actually published (e.g. via a WebFetch of `https://developers.google.com/ml-kit/vision/text-recognition/v2/android` or a real build log's resolution error) and correct the version, not guess again.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Add ML Kit Text Recognition dependency for nutrition-label OCR"
```

---

### Task 2: `NutritionLabelParser` domain logic

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/domain/NutritionLabelParser.kt`
- Test: `app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt`

**Interfaces:**
- Produces:
  - `data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)` — plain Kotlin representation of one recognized line of text plus its bounding box, so this file has zero dependency on the ML Kit SDK types and can be unit-tested without Android/Robolectric.
  - `data class ParsedNutritionLabel(val calories: Double? = null, val proteinG: Double? = null, val carbsG: Double? = null, val fatG: Double? = null, val servingQty: Double? = null, val servingUnit: String? = null)` with a `val isEmpty: Boolean` computed property (true when all six fields are null).
  - `object NutritionLabelParser { fun parse(lines: List<OcrLine>): ParsedNutritionLabel }`
- Consumes: nothing from other tasks (this is the first pure-logic task; Task 3 will consume this file's public types).

This task has several TDD cycles against the same two files — one per parsing behavior. Write each test, watch it fail for the right reason, then add just enough implementation to pass it, before moving to the next.

- [ ] **Step 1: Write the failing test for the basic happy path**

Create `app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt`:

```kotlin
package com.macrotrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelParserTest {

    @Test
    fun parsesACleanFourRowLabel() {
        val lines = listOf(
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("124Cal (520kJ)", left = 200, top = 100, right = 320, bottom = 120),
            OcrLine("Protein", left = 0, top = 130, right = 80, bottom = 150),
            OcrLine("3.2g", left = 200, top = 130, right = 250, bottom = 150),
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("2.1g", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("Carbohydrate", left = 0, top = 190, right = 100, bottom = 210),
            OcrLine("15.6g", left = 200, top = 190, right = 260, bottom = 210),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(124.0, result.calories!!, 0.001)
        assertEquals(3.2, result.proteinG!!, 0.001)
        assertEquals(2.1, result.fatG!!, 0.001)
        assertEquals(15.6, result.carbsG!!, 0.001)
        assertNull(result.servingQty)
        assertNull(result.servingUnit)
        assertTrue(!result.isEmpty)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: FAIL — `OcrLine`, `ParsedNutritionLabel`, and `NutritionLabelParser` don't exist yet (compile error).

- [ ] **Step 3: Write the implementation to make it pass**

Create `app/src/main/java/com/macrotrack/app/domain/NutritionLabelParser.kt`:

```kotlin
package com.macrotrack.app.domain

/**
 * One recognized line of text and its bounding box, decoupled from ML Kit's
 * own `Text.Line` type so this parser has zero Android/ML-Kit dependency and
 * can be unit-tested with plain fixtures. The UI layer maps ML Kit's result
 * into these before calling [NutritionLabelParser.parse].
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Result of attempting to read a nutrition panel photo. Every field is
 * independently nullable: a field the parser isn't confident about is left
 * null rather than guessed (CLAUDE.md rule #1), and the caller must leave
 * that form field blank/unchanged rather than inventing a value.
 */
data class ParsedNutritionLabel(
    val calories: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val servingQty: Double? = null,
    val servingUnit: String? = null,
) {
    val isEmpty: Boolean
        get() = calories == null && proteinG == null && carbsG == null &&
            fatG == null && servingQty == null && servingUnit == null
}

/**
 * Parses OCR'd nutrition-panel text into structured macros. Australian
 * panels lay the "per serving" and "per 100g" columns out as a table; OCR
 * frequently returns the label and its two value columns as separate lines
 * rather than one line of table-aware text, and can return them out of
 * left-to-right/top-to-bottom order. This groups lines into rows by vertical
 * position first (not by text order), then reads left-to-right within a row,
 * so table-shape errors in the OCR pass don't silently produce a wrong
 * label/value pairing.
 */
object NutritionLabelParser {
    /** Same conversion this repo's importer uses (`seed_common.KJ_PER_KCAL`), kept in sync for consistency. */
    private const val KJ_PER_KCAL = 4.184

    fun parse(lines: List<OcrLine>): ParsedNutritionLabel {
        val rows = groupIntoRows(lines)
        var calories: Double? = null
        var proteinG: Double? = null
        var fatG: Double? = null
        var carbsG: Double? = null

        for (row in rows) {
            val label = row.getOrNull(0)?.text ?: continue
            val valueCell = row.getOrNull(1)?.text ?: continue
            when {
                calories == null && isEnergyLabel(label) -> calories = parseEnergyKcal(valueCell)
                proteinG == null && isProteinLabel(label) -> proteinG = firstNumber(valueCell)
                fatG == null && isFatTotalLabel(label) -> fatG = firstNumber(valueCell)
                carbsG == null && isCarbohydrateTotalLabel(label) -> carbsG = firstNumber(valueCell)
            }
        }

        val serving = parseServingSize(lines)
        return ParsedNutritionLabel(
            calories = calories,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            servingQty = serving?.first,
            servingUnit = serving?.second,
        )
    }

    private fun groupIntoRows(lines: List<OcrLine>, verticalTolerancePx: Int = 12): List<List<OcrLine>> {
        val sorted = lines.sortedBy { verticalCenter(it) }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val center = verticalCenter(line)
            val currentRow = rows.lastOrNull()
            val rowCenter = currentRow?.map { verticalCenter(it) }?.average()
            if (currentRow != null && rowCenter != null && kotlin.math.abs(center - rowCenter) <= verticalTolerancePx) {
                currentRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    private fun verticalCenter(line: OcrLine): Double = (line.top + line.bottom) / 2.0

    private fun isEnergyLabel(text: String) = text.trim().lowercase().startsWith("energy")
    private fun isProteinLabel(text: String) = text.trim().lowercase().startsWith("protein")

    private fun isFatTotalLabel(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("fat") && "saturated" !in normalized
    }

    private fun isCarbohydrateTotalLabel(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("carbohydrate") && "sugar" !in normalized
    }

    private val NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

    private fun firstNumber(text: String): Double? = NUMBER_REGEX.find(text)?.value?.toDoubleOrNull()

    private fun parseEnergyKcal(cellText: String): Double? {
        val lower = cellText.lowercase()
        val calMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:kcal|cal)\b""").find(lower)
        if (calMatch != null) return calMatch.groupValues[1].toDoubleOrNull()
        val kjMatch = Regex("""(\d+(?:\.\d+)?)\s*kj\b""").find(lower)
        return kjMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { it / KJ_PER_KCAL }
    }

    private val SERVING_SIZE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(g|ml|mg|kg|l)\b""", RegexOption.IGNORE_CASE)

    private fun parseServingSize(lines: List<OcrLine>): Pair<Double, String>? {
        val servingLine = lines.firstOrNull { "serving size" in it.text.lowercase() } ?: return null
        val parenMatch = Regex("""\(([^)]*)\)""").find(servingLine.text)
        val searchText = parenMatch?.groupValues?.get(1) ?: servingLine.text
        val match = SERVING_SIZE_REGEX.find(searchText) ?: return null
        val qty = match.groupValues[1].toDoubleOrNull() ?: return null
        return qty to match.groupValues[2].lowercase()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/domain/NutritionLabelParser.kt app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt
git commit -m "Add NutritionLabelParser with a passing happy-path test"
```

- [ ] **Step 6: Write the failing test for kJ-only energy**

Add to `NutritionLabelParserTest.kt`:

```kotlin
    @Test
    fun convertsKilojoulesToCaloriesWhenNoCalReadingIsPresent() {
        val lines = listOf(
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("836kJ", left = 200, top = 100, right = 260, bottom = 120),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(199.809, result.calories!!, 0.01)
    }
```

- [ ] **Step 7: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: FAIL if it doesn't already pass with Step 3's implementation — `parseEnergyKcal` already handles this case, so this may in fact PASS immediately. If it passes immediately, that confirms Step 3's implementation already covers this behavior; note that in the commit message instead of skipping the test.

- [ ] **Step 8: Confirm and commit**

```bash
git add app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt
git commit -m "Add test: energy falls back to kJ->kcal conversion when no Cal reading exists"
```

- [ ] **Step 9: Write the failing test for fat/carb sub-row disambiguation**

Add to `NutritionLabelParserTest.kt`:

```kotlin
    @Test
    fun doesNotConfuseSaturatedFatOrSugarsWithTheTotalRow() {
        val lines = listOf(
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("9.4g", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("- saturated", left = 10, top = 190, right = 100, bottom = 210),
            OcrLine("6.1g", left = 200, top = 190, right = 250, bottom = 210),
            OcrLine("Carbohydrate", left = 0, top = 220, right = 100, bottom = 240),
            OcrLine("22.0g", left = 200, top = 220, right = 260, bottom = 240),
            OcrLine("- sugars", left = 10, top = 250, right = 100, bottom = 270),
            OcrLine("18.5g", left = 200, top = 250, right = 260, bottom = 270),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(9.4, result.fatG!!, 0.001)
        assertEquals(22.0, result.carbsG!!, 0.001)
    }
```

- [ ] **Step 10: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: this exercises `isFatTotalLabel`/`isCarbohydrateTotalLabel`'s existing "saturated"/"sugar" exclusion — it may already PASS. If it fails, the bug is almost certainly that the sub-row's number is overwriting the total row's; fix by keeping the `calories == null && ...` / `proteinG == null && ...` style guards already in `parse()`'s `when` block (first match wins, and the total row is expected to appear before its sub-row on a real label) — do not weaken the label-matching regex to "fix" this by making sub-rows fail to match at all, since a genuinely mis-ordered photo should still be caught by row order, not by accident.

- [ ] **Step 11: Confirm and commit**

```bash
git add app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt
git commit -m "Add test: fat/carbohydrate sub-rows don't overwrite the total row"
```

- [ ] **Step 12: Write the failing test for jumbled line order**

Add to `NutritionLabelParserTest.kt`:

```kotlin
    @Test
    fun survivesLinesReturnedOutOfTopToBottomOrder() {
        // Same four rows as parsesACleanFourRowLabel, but shuffled - OCR does
        // not guarantee it returns lines in reading order.
        val lines = listOf(
            OcrLine("15.6g", left = 200, top = 190, right = 260, bottom = 210),
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("Carbohydrate", left = 0, top = 190, right = 100, bottom = 210),
            OcrLine("2.1g", left = 200, top = 160, right = 250, bottom = 180),
            OcrLine("124Cal (520kJ)", left = 200, top = 100, right = 320, bottom = 120),
            OcrLine("Fat, total", left = 0, top = 160, right = 90, bottom = 180),
            OcrLine("Protein", left = 0, top = 130, right = 80, bottom = 150),
            OcrLine("3.2g", left = 200, top = 130, right = 250, bottom = 150),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(124.0, result.calories!!, 0.001)
        assertEquals(3.2, result.proteinG!!, 0.001)
        assertEquals(2.1, result.fatG!!, 0.001)
        assertEquals(15.6, result.carbsG!!, 0.001)
    }
```

- [ ] **Step 13: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: this specifically exercises `groupIntoRows`'s sort-by-vertical-position-first behavior from Step 3 — it should PASS given that implementation. If it fails, the row grouping is not correctly independent of input order; the fix is in `groupIntoRows`, not in the row-matching logic.

- [ ] **Step 14: Confirm and commit**

```bash
git add app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt
git commit -m "Add test: row grouping is independent of OCR line order"
```

- [ ] **Step 15: Write the failing test for serving-size parsing**

Add to `NutritionLabelParserTest.kt`:

```kotlin
    @Test
    fun parsesServingSizeFromParentheticalGrams() {
        val lines = listOf(
            OcrLine("Serving size: 2 biscuits (30g)", left = 0, top = 50, right = 300, bottom = 70),
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("124Cal", left = 200, top = 100, right = 260, bottom = 120),
        )

        val result = NutritionLabelParser.parse(lines)

        assertEquals(30.0, result.servingQty!!, 0.001)
        assertEquals("g", result.servingUnit)
    }

    @Test
    fun leavesServingSizeNullWhenNoServingSizeLineIsPresent() {
        val lines = listOf(
            OcrLine("Energy", left = 0, top = 100, right = 80, bottom = 120),
            OcrLine("124Cal", left = 200, top = 100, right = 260, bottom = 120),
        )

        val result = NutritionLabelParser.parse(lines)

        assertNull(result.servingQty)
        assertNull(result.servingUnit)
    }
```

- [ ] **Step 16: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: exercises `parseServingSize` from Step 3 — should PASS given that implementation.

- [ ] **Step 17: Confirm and commit**

```bash
git add app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt
git commit -m "Add tests: serving-size parsing from parenthetical grams, and its absence"
```

- [ ] **Step 18: Write the failing test for total parse failure**

Add to `NutritionLabelParserTest.kt`:

```kotlin
    @Test
    fun returnsAnEmptyResultWhenNothingRecognizableIsFound() {
        val lines = listOf(
            OcrLine("Ingredients: wheat flour, sugar, vegetable oil", left = 0, top = 300, right = 400, bottom = 320),
            OcrLine("Best before 12/2027", left = 0, top = 340, right = 200, bottom = 360),
        )

        val result = NutritionLabelParser.parse(lines)

        assertTrue(result.isEmpty)
    }
```

- [ ] **Step 19: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: should PASS given Step 3's implementation (none of these lines match any label keyword, so every field stays null and `isEmpty` is true).

- [ ] **Step 20: Confirm and commit**

```bash
git add app/src/test/java/com/macrotrack/app/domain/NutritionLabelParserTest.kt
git commit -m "Add test: unrecognizable photo text yields an empty (all-null) result"
```

- [ ] **Step 21: Full test file run**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.domain.NutritionLabelParserTest`
Expected: all 8 tests PASS.

---

### Task 3: `NutritionLabelScannerScreen`

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/search/NutritionLabelScannerScreen.kt`

**Interfaces:**
- Consumes: `com.macrotrack.app.domain.OcrLine`, `ParsedNutritionLabel`, `NutritionLabelParser.parse(lines: List<OcrLine>): ParsedNutritionLabel` (Task 2).
- Produces: `@Composable fun NutritionLabelScannerScreen(onResult: (ParsedNutritionLabel) -> Unit, onClose: () -> Unit)` — later consumed by Task 6's nav wiring.

No unit test for this file: it's a Compose screen driving CameraX and ML Kit, exactly like `BarcodeScannerScreen`, which also has no test — this project validates that class of screen manually on-device (see `docs/superpowers/specs/2026-08-04-nutrition-label-ocr-design.md`'s Testing section and the barcode scanner's own untested precedent).

- [ ] **Step 1: Write the screen**

Create `app/src/main/java/com/macrotrack/app/ui/search/NutritionLabelScannerScreen.kt`:

```kotlin
package com.macrotrack.app.ui.search

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.macrotrack.app.domain.NutritionLabelParser
import com.macrotrack.app.domain.OcrLine
import com.macrotrack.app.domain.ParsedNutritionLabel
import java.util.concurrent.Executors

/**
 * Captures one still photo of a nutrition panel, runs on-device ML Kit Text
 * Recognition on it, and hands a parsed result back via [onResult]. The
 * bitmap is never persisted or uploaded - only recognized text leaves this
 * screen. Mirrors BarcodeScannerScreen's camera setup and permission flow.
 */
@Composable
fun NutritionLabelScannerScreen(
    onResult: (ParsedNutritionLabel) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasRequestedPermissionOnce by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showRetryPrompt by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            hasRequestedPermissionOnce = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        val canShowSystemRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } ?: false
        val isPermanentlyDenied = hasRequestedPermissionOnce && !canShowSystemRationale

        NutritionLabelPermissionContent(
            isPermanentlyDenied = isPermanentlyDenied,
            onRequestPermission = {
                hasRequestedPermissionOnce = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenAppSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
            onClose = onClose,
        )
        return
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        DisposableEffect(previewView, lifecycleOwner) {
            val callbackExecutor = ContextCompat.getMainExecutor(context)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            var cameraProvider: ProcessCameraProvider? = null
            var disposed = false

            val listener = Runnable {
                if (disposed) return@Runnable
                try {
                    cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                        .also { imageCapture = it }

                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                } catch (e: Exception) {
                    cameraError = e.message ?: "The camera could not be started."
                }
            }

            cameraProviderFuture.addListener(listener, callbackExecutor)

            onDispose {
                disposed = true
                cameraProvider?.unbindAll()
            }
        }

        NutritionLabelOverlay(
            cameraError = cameraError,
            isProcessing = isProcessing,
            showRetryPrompt = showRetryPrompt,
            onCapture = {
                val capture = imageCapture ?: return@NutritionLabelOverlay
                isProcessing = true
                showRetryPrompt = false
                captureAndRecognize(
                    imageCapture = capture,
                    executor = Executors.newSingleThreadExecutor(),
                    mainExecutor = ContextCompat.getMainExecutor(context),
                    onLines = { lines ->
                        isProcessing = false
                        val result = NutritionLabelParser.parse(lines)
                        if (result.isEmpty) {
                            showRetryPrompt = true
                        } else {
                            currentOnResult(result)
                        }
                    },
                    onError = {
                        isProcessing = false
                        showRetryPrompt = true
                    },
                )
            },
            onClose = onClose,
        )
    }
}

private fun captureAndRecognize(
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    mainExecutor: java.util.concurrent.Executor,
    onLines: (List<OcrLine>) -> Unit,
    onError: () -> Unit,
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            @androidx.camera.core.ExperimentalGetImage
            override fun onCaptureSuccess(image: ImageProxy) {
                val mediaImage = image.image
                if (mediaImage == null) {
                    image.close()
                    mainExecutor.execute(onError)
                    return
                }
                val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(inputImage)
                    .addOnSuccessListener(mainExecutor) { text ->
                        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            line.boundingBox?.let { box ->
                                OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                            }
                        }
                        onLines(lines)
                    }
                    .addOnFailureListener(mainExecutor) { onError() }
                    .addOnCompleteListener(mainExecutor) { image.close() }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute(onError)
            }
        },
    )
}

@Composable
private fun NutritionLabelOverlay(
    cameraError: String?,
    isProcessing: Boolean,
    showRetryPrompt: Boolean,
    onCapture: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onClose) {
                Text("Close")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Frame the nutrition information panel, then tap Capture",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )

        if (showRetryPrompt) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = "Couldn't read that label - try again?",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (cameraError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = cameraError,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onCapture, enabled = !isProcessing) {
            if (isProcessing) CircularProgressIndicator() else Text("Capture")
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NutritionLabelPermissionContent(
    isPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isPermanentlyDenied) {
            Text(
                "Camera access was denied. Enable it from app settings to scan a nutrition label.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenAppSettings) {
                Text("Open app settings")
            }
        } else {
            Text("Camera access is needed to scan a nutrition label.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Allow camera")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onClose) {
            Text("Enter manually")
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/search/NutritionLabelScannerScreen.kt
git commit -m "Add NutritionLabelScannerScreen (camera capture + on-device OCR)"
```

---

### Task 4: Wire the parsed result into `CreateCustomFoodViewModel`

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/ui/search/CreateCustomFoodViewModel.kt`
- Test: `app/src/test/java/com/macrotrack/app/ui/search/CreateCustomFoodViewModelTest.kt` (new file - none exists for this ViewModel today; check first in case one was added since this plan was written)

**Interfaces:**
- Consumes: `com.macrotrack.app.domain.ParsedNutritionLabel` (Task 2).
- Produces: `CreateCustomFoodViewModel.onNutritionLabelScanned(result: ParsedNutritionLabel)` — consumed by Task 6's nav wiring.

- [ ] **Step 1: Write the failing test**

First check whether `app/src/test/java/com/macrotrack/app/ui/search/CreateCustomFoodViewModelTest.kt` already exists; if it does, add this test to it instead of creating a new file, matching whatever fake/fixture `CustomFoodRepository` it already uses. If it doesn't exist, create it with a minimal fake repository:

```kotlin
package com.macrotrack.app.ui.search

import com.macrotrack.app.data.CustomFoodRepository
import com.macrotrack.app.data.model.CustomFood
import com.macrotrack.app.domain.ParsedNutritionLabel
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeCustomFoodRepository : CustomFoodRepository {
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
    ): CustomFood = throw NotImplementedError("not exercised by this test")
}

class CreateCustomFoodViewModelTest {

    @Test
    fun prefillsBlankFieldsFromAScannedLabelButNeverOverwritesUserEnteredValues() {
        val viewModel = CreateCustomFoodViewModel(FakeCustomFoodRepository())
        viewModel.onProteinChanged("50") // user already typed this in before scanning

        viewModel.onNutritionLabelScanned(
            ParsedNutritionLabel(
                calories = 200.0,
                proteinG = 8.0,
                carbsG = null,
                fatG = 3.2,
                servingQty = 30.0,
                servingUnit = "g",
            ),
        )

        val state = viewModel.uiState.value
        assertEquals("200.0", state.calories)
        assertEquals("50", state.proteinG) // untouched - user's own entry wins
        assertEquals("", state.carbsG) // parser was null, stays blank
        assertEquals("3.2", state.fatG)
        assertEquals("30.0", state.servingQty)
        assertEquals("g", state.servingUnit)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.ui.search.CreateCustomFoodViewModelTest`
Expected: FAIL — `onNutritionLabelScanned` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

In `CreateCustomFoodViewModel.kt`, add the import and the new function:

```kotlin
import com.macrotrack.app.domain.ParsedNutritionLabel
```

```kotlin
    /**
     * Pre-fills only the fields the parser was confident about, and only if
     * the user hasn't already typed something into that field - a scan never
     * overwrites a value the user already entered, matching the "never
     * silently overwrite" rule this app applies everywhere OCR/import
     * touches user-facing data.
     */
    fun onNutritionLabelScanned(result: ParsedNutritionLabel) {
        _uiState.value = _uiState.value.let { state ->
            state.copy(
                calories = state.calories.ifBlank { result.calories?.toString() ?: "" },
                proteinG = state.proteinG.ifBlank { result.proteinG?.toString() ?: "" },
                carbsG = state.carbsG.ifBlank { result.carbsG?.toString() ?: "" },
                fatG = state.fatG.ifBlank { result.fatG?.toString() ?: "" },
                servingQty = state.servingQty.ifBlank { result.servingQty?.toString() ?: "" },
                servingUnit = state.servingUnit.ifBlank { result.servingUnit ?: "" },
                errorMessage = null,
            )
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.macrotrack.app.ui.search.CreateCustomFoodViewModelTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/search/CreateCustomFoodViewModel.kt app/src/test/java/com/macrotrack/app/ui/search/CreateCustomFoodViewModelTest.kt
git commit -m "Add CreateCustomFoodViewModel.onNutritionLabelScanned"
```

---

### Task 5: Add the "Scan nutrition label" button to `CreateCustomFoodScreen`

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/ui/search/CreateCustomFoodScreen.kt`

**Interfaces:**
- Consumes: a new `onScanNutritionLabel: () -> Unit` parameter (navigation is the nav host's job, per this app's existing pattern where screens take callbacks, not `NavController` references directly - see how `FoodSearchScreen` takes `onScanBarcode: () -> Unit`).
- Produces: nothing further consumed by other tasks in this plan; Task 6 supplies the callback.

No test for this step - it's markup/wiring in an existing untested Composable (`CreateCustomFoodScreen` itself has no test file today), consistent with this file's current state.

- [ ] **Step 1: Add the parameter and button**

In `CreateCustomFoodScreen.kt`, change the function signature:

```kotlin
@Composable
fun CreateCustomFoodScreen(
    viewModel: CreateCustomFoodViewModel,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
    onScanNutritionLabel: () -> Unit,
) {
```

Then, right after the `item { Text("Nutrition for that serving", ...) }` line and before the four `NutritionField(...)` items, add:

```kotlin
        item {
            OutlinedButton(onClick = onScanNutritionLabel, modifier = Modifier.fillMaxWidth()) {
                Text("Scan nutrition label")
            }
        }
```

Add the missing import alongside the existing `androidx.compose.material3.*` imports:

```kotlin
import androidx.compose.material3.OutlinedButton
```

- [ ] **Step 2: Verify it still compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAILS at this point, because `MacroTrackNavHost.kt` (Task 6) hasn't been updated yet to pass the new `onScanNutritionLabel` argument - `CreateCustomFoodScreen`'s only call site now has a missing required parameter. This is expected; Task 6 fixes it. Do not add a default value to `onScanNutritionLabel` to paper over this - the nav host is supposed to wire real navigation here, and a silently-accepted no-op default would hide that if Task 6 were ever skipped.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/search/CreateCustomFoodScreen.kt
git commit -m "Add Scan nutrition label button to CreateCustomFoodScreen (nav wiring in next task)"
```

---

### Task 6: Wire the navigation route and consume the scanned result

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/ui/nav/Destinations.kt`
- Modify: `app/src/main/java/com/macrotrack/app/ui/nav/MacroTrackNavHost.kt`

**Interfaces:**
- Consumes: `NutritionLabelScannerScreen` (Task 3), `CreateCustomFoodViewModel.onNutritionLabelScanned` (Task 4), `CreateCustomFoodScreen`'s `onScanNutritionLabel` parameter (Task 5).
- Produces: nothing further - this is the last task, it closes the loop.

This task passes the six `ParsedNutritionLabel` fields back across the navigation boundary as individual primitive `SavedStateHandle` entries (`Double?`/`String?`), the same way `SCANNED_BARCODE_KEY` already passes a single `String?` - not as one serialized object, to avoid adding a Parcelable/serialization dependency for a same-transition, in-memory-only hop.

- [ ] **Step 1: Add route + keys to `Destinations.kt`**

In `Destinations.kt`, add alongside the existing `BARCODE_SCANNER`/`SCANNED_BARCODE_KEY` constants:

```kotlin
    const val NUTRITION_LABEL_SCANNER = "nutrition_label_scanner"
    const val SCANNED_LABEL_CALORIES_KEY = "scanned_label_calories"
    const val SCANNED_LABEL_PROTEIN_KEY = "scanned_label_protein"
    const val SCANNED_LABEL_CARBS_KEY = "scanned_label_carbs"
    const val SCANNED_LABEL_FAT_KEY = "scanned_label_fat"
    const val SCANNED_LABEL_SERVING_QTY_KEY = "scanned_label_serving_qty"
    const val SCANNED_LABEL_SERVING_UNIT_KEY = "scanned_label_serving_unit"
```

- [ ] **Step 2: Update the `CreateCustomFoodScreen` call site in `MacroTrackNavHost.kt`**

Find the existing `composable(route = Destinations.CREATE_CUSTOM_FOOD_PATTERN, ...)` block. Replace its body with:

```kotlin
                    composable(
                        route = Destinations.CREATE_CUSTOM_FOOD_PATTERN,
                        arguments = listOf(navArgument("logDate") { type = NavType.StringType }),
                    ) { createFoodBackStackEntry ->
                        val logDate = LocalDate.parse(
                            createFoodBackStackEntry.arguments?.getString("logDate").orEmpty(),
                        )
                        val createViewModel: CreateCustomFoodViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    CreateCustomFoodViewModel(
                                        repository = appContainer.customFoodRepository,
                                    )
                                }
                            },
                        )

                        val scannedCalories by createFoodBackStackEntry.savedStateHandle
                            .getStateFlow<Double?>(Destinations.SCANNED_LABEL_CALORIES_KEY, null)
                            .collectAsState()
                        val scannedProtein by createFoodBackStackEntry.savedStateHandle
                            .getStateFlow<Double?>(Destinations.SCANNED_LABEL_PROTEIN_KEY, null)
                            .collectAsState()
                        val scannedCarbs by createFoodBackStackEntry.savedStateHandle
                            .getStateFlow<Double?>(Destinations.SCANNED_LABEL_CARBS_KEY, null)
                            .collectAsState()
                        val scannedFat by createFoodBackStackEntry.savedStateHandle
                            .getStateFlow<Double?>(Destinations.SCANNED_LABEL_FAT_KEY, null)
                            .collectAsState()
                        val scannedServingQty by createFoodBackStackEntry.savedStateHandle
                            .getStateFlow<Double?>(Destinations.SCANNED_LABEL_SERVING_QTY_KEY, null)
                            .collectAsState()
                        val scannedServingUnit by createFoodBackStackEntry.savedStateHandle
                            .getStateFlow<String?>(Destinations.SCANNED_LABEL_SERVING_UNIT_KEY, null)
                            .collectAsState()

                        LaunchedEffect(scannedCalories, scannedProtein, scannedCarbs, scannedFat, scannedServingQty, scannedServingUnit) {
                            val gotAnyField = listOf(
                                scannedCalories, scannedProtein, scannedCarbs, scannedFat, scannedServingQty,
                            ).any { it != null } || scannedServingUnit != null
                            if (gotAnyField) {
                                createViewModel.onNutritionLabelScanned(
                                    ParsedNutritionLabel(
                                        calories = scannedCalories,
                                        proteinG = scannedProtein,
                                        carbsG = scannedCarbs,
                                        fatG = scannedFat,
                                        servingQty = scannedServingQty,
                                        servingUnit = scannedServingUnit,
                                    ),
                                )
                                createFoodBackStackEntry.savedStateHandle[Destinations.SCANNED_LABEL_CALORIES_KEY] = null
                                createFoodBackStackEntry.savedStateHandle[Destinations.SCANNED_LABEL_PROTEIN_KEY] = null
                                createFoodBackStackEntry.savedStateHandle[Destinations.SCANNED_LABEL_CARBS_KEY] = null
                                createFoodBackStackEntry.savedStateHandle[Destinations.SCANNED_LABEL_FAT_KEY] = null
                                createFoodBackStackEntry.savedStateHandle[Destinations.SCANNED_LABEL_SERVING_QTY_KEY] = null
                                createFoodBackStackEntry.savedStateHandle[Destinations.SCANNED_LABEL_SERVING_UNIT_KEY] = null
                            }
                        }

                        CreateCustomFoodScreen(
                            viewModel = createViewModel,
                            onSaved = { id ->
                                navController.navigate(
                                    Destinations.addLogEntryRoute(EntryKind.CUSTOM_FOOD, id, logDate),
                                ) {
                                    popUpTo(Destinations.createCustomFoodRoute(logDate)) { inclusive = true }
                                }
                            },
                            onCancel = { navController.popBackStack() },
                            onScanNutritionLabel = { navController.navigate(Destinations.NUTRITION_LABEL_SCANNER) },
                        )
                    }
```

Add the import:

```kotlin
import com.macrotrack.app.domain.ParsedNutritionLabel
```

- [ ] **Step 3: Add the new scanner route**

Right after the existing `composable(Destinations.BARCODE_SCANNER) { ... }` block, add:

```kotlin
                    composable(Destinations.NUTRITION_LABEL_SCANNER) {
                        NutritionLabelScannerScreen(
                            onResult = { result ->
                                val previous = navController.previousBackStackEntry?.savedStateHandle
                                previous?.set(Destinations.SCANNED_LABEL_CALORIES_KEY, result.calories)
                                previous?.set(Destinations.SCANNED_LABEL_PROTEIN_KEY, result.proteinG)
                                previous?.set(Destinations.SCANNED_LABEL_CARBS_KEY, result.carbsG)
                                previous?.set(Destinations.SCANNED_LABEL_FAT_KEY, result.fatG)
                                previous?.set(Destinations.SCANNED_LABEL_SERVING_QTY_KEY, result.servingQty)
                                previous?.set(Destinations.SCANNED_LABEL_SERVING_UNIT_KEY, result.servingUnit)
                                navController.popBackStack()
                            },
                            onClose = { navController.popBackStack() },
                        )
                    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS. This resolves the expected failure from Task 5 Step 2.

- [ ] **Step 5: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing tests plus this plan's new ones).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/nav/Destinations.kt app/src/main/java/com/macrotrack/app/ui/nav/MacroTrackNavHost.kt
git commit -m "Wire nutrition-label scanner navigation into Create Custom Food"
```

---

## After all tasks

- Push the branch and let the `Android build` GitHub Actions workflow (`.github/workflows/android-build.yml`) run for real compile/assemble/unit-test confirmation - this sandbox cannot run Gradle (no Android SDK, network policy blocks Google's Maven), so CI is the actual verification, same as the rest of this app.
- Update `docs/HANDOFF.md`'s "What's NOT done" section: nutrition-label OCR moves from "design proposed, not built" to "implemented, not yet tested on a physical device" - explicitly still flag the "never compiled on a real device" gap this shares with the barcode scanner, don't overclaim.
- Real accuracy tuning (the `verticalTolerancePx = 12` constant, the label-keyword matching) can only happen against actual photographed labels on a physical device - flag this clearly rather than treating CI-green as "done."
