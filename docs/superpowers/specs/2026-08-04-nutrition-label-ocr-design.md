# Nutrition-label OCR — design

Status: approved by user, proceeding to implementation plan.

## Purpose

When a barcode scan misses (the product isn't in `foods` or `custom_foods`),
the user lands on Create Custom Food and currently has to type calories,
protein, carbs, and fat by hand off the packaging. This feature lets them
instead photograph the nutrition panel and have those four fields pre-filled
automatically, still fully editable before saving. It reuses the CameraX
capture infrastructure already built for barcode scanning and follows the
same non-negotiable rule as the rest of this app: never invent a number, and
never save anything without the user reviewing/confirming it first.

## Scope (v1)

- **Macros only** — calories, protein, carbs, fat. Micronutrients are an
  explicit non-goal for this version: the Create Custom Food form has no
  micronutrient fields today, and adding them is a separate scope decision
  (which micros, new form section) left for later.
- **Entry point**: a "Scan nutrition label" button on the existing
  `CreateCustomFoodScreen`, next to the macro fields. The barcode field is
  typically already filled in from the barcode-miss flow that led here, but
  the button is available regardless of how the user arrived at the screen.
- **On-device only**: ML Kit Text Recognition, no cloud OCR API, no network
  call for the photo. Consistent with this repo's existing barcode-scanning
  approach and the CLAUDE.md rule to keep camera features native and
  on-device.

## Architecture / flow

1. User taps "Scan nutrition label" on `CreateCustomFoodScreen`.
2. A new `NutritionLabelScannerScreen` opens — same CameraX
   preview/capture structure as `BarcodeScannerScreen`, but captures a single
   still photo instead of running continuous barcode detection.
3. ML Kit Text Recognition runs on the captured bitmap, on-device, returning
   a `Text` result with per-line bounding boxes (not just flattened string
   content).
4. The photo (bitmap) is discarded immediately after this call. It is never
   uploaded, never written to disk beyond whatever ML Kit's transient
   processing requires, never attached to the food record.
5. A new pure-Kotlin `NutritionLabelParser` (in `domain/`, no Android
   framework dependency, same style as `ServingScaler`/`AdaptiveEngine`)
   takes the `Text` result and produces a `ParsedNutritionLabel` result
   (calories, protein, carbs, fat, servingQty, servingUnit — each nullable
   independently).
6. The screen passes that result back to `CreateCustomFoodViewModel`, which
   pre-fills its existing form state fields. The user reviews/edits every
   field and taps the existing Save button — no new save path, no silent
   overwrite.

## Parsing design

Australian nutrition panels are a two-column table: "per serving" and "per
100g", with rows for Energy, Protein, Fat (and sub-rows), Carbohydrate (and
sub-rows), Sodium, etc.

- **Spatial table reconstruction**: group OCR'd lines into rows by vertical
  (y-axis) position rather than relying on flattened text order, so a
  misordered OCR read of the two columns doesn't corrupt row/value pairing.
  Within a row, use horizontal (x-axis) position to distinguish the "per
  serving" column from the "per 100g" column.
- **Column choice**: use the **per serving** column (not per 100g) for
  calories/protein/carbs/fat, since custom foods store macros directly
  against whatever `serving_qty`/`serving_unit` is set (there's no separate
  100g-basis column for custom foods the way there is for imported `foods`
  rows).
- **Serving size parsing**: also parse the label's free-text serving-size
  string (e.g. "2 biscuits (30g)", "1 serve (250 mL)") into `serving_qty` +
  `serving_unit`. If this can't be confidently resolved into a number + unit,
  leave those two fields at their existing defaults rather than guessing.
- **Energy/calories**: prefer a kcal/Cal number if the panel shows one;
  convert from kJ only when kcal is absent — the same rule
  `import_openfoodfacts.py` already applies, for consistency across the
  codebase.
- **Row-label matching**: match against known label keywords (Energy,
  Protein, Fat total/saturated, Carbohydrate total/sugars) via
  case-insensitive matching tolerant of common OCR substitutions.
- **Unresolved fields**: any row/value the parser isn't confident about is
  simply omitted from the result. The caller leaves that field blank — no
  guessing, no partial/low-confidence value ever gets written into a field.

## UI / UX details

- **Per-field confidence**: no special visual treatment. A blank field reads
  the same as if the user had started from scratch — consistent with "never
  guess."
- **Whole-photo failure** (parser finds no usable table structure at all):
  show a "Couldn't read that label — try again?" prompt with a retake-photo
  action, rather than silently dropping through to a blank form. This is the
  one case that gets explicit failure UI, because the alternative (silently
  returning to an unchanged form) would look identical to the button having
  done nothing.

## Testing

No camera or ML Kit call exists in CI or any sandbox available for this
project, so:

- `NutritionLabelParser` is unit-tested directly against hand-built
  `Text`-shaped fixtures (rows + bounding boxes constructed to mimic real
  label layouts), including deliberately adversarial cases: jumbled line
  order, missing rows, only-kJ energy, unusual serving-size phrasing, and a
  photo with no recognizable table at all (asserting the "whole-photo
  failure" result).
- This mirrors the existing testing style in this repo (`ServingScaler`,
  `WeightTrendCalculator`, etc. — pure-function domain logic tested without
  any Android framework dependency).
- Real-world OCR accuracy against actual photographed labels can only be
  validated on a physical device — same caveat already recorded for the
  barcode scanner, which has also never been tested on real hardware.

## Explicitly out of scope for v1

- Micronutrients.
- Persisting or uploading the label photo.
- A confirm-before-processing step after capture (matches the existing
  barcode scanner's immediate-processing UX).
- Any cloud/LLM-based parsing — this was already ruled out for the OCR step
  itself in an earlier design note, and extending a network dependency to
  the parsing step would conflict with the same reasoning (cost, latency,
  and the project's preference for deterministic, explainable local logic
  over an opaque cloud call).
