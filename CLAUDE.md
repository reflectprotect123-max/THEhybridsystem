# Claude Code instructions — Macro+

You are taking over a new repository for an Android nutrition tracker named
Macro+.

## Product target

Build an Android app in Kotlin/Jetpack Compose with a Supabase/Postgres
backend. The target is MacroFactor-class functionality: fast food logging,
Australian barcode coverage, recipes/custom foods, micronutrients, weight
trending, expenditure estimation, adaptive calorie/macro targets, weekly
check-ins, body metrics, and a calm explainable coaching experience.

Do not describe this repository as a finished MacroFactor clone. MacroFactor's
exact production parameters are private. Use the public behaviour as product
inspiration and keep every local algorithm configurable, deterministic,
versioned, and explainable.

## Source of truth

* `README.md` — setup and exact commands.
* `supabase/migrations/001_macro_foundation.sql` — database contract.
* `adaptive_engine.py` — executable reference for the adaptive logic.
* `docs/ADAPTIVE_ENGINE_CONTRACT.md` — Kotlin handoff contract and evidence
  boundaries.
* `import_openfoodfacts.py`, `import_ausnut.py`, `seed_common.py` — reproducible
  data import pipeline.
* `tests/` — offline regression expectations.
* `app/` — Android starter module.

If implementation and documentation disagree, stop and reconcile them instead
of silently choosing one.

## First commands

Run these before changing behaviour:

```bash
python3 -m unittest discover -s tests -v
python3 -m py_compile *.py
git status --short
```

The current coding workspace may not have an Android SDK or Gradle installed.
If Android compilation cannot run, say so explicitly and still validate the
Python/schema-side work. On an Android machine, sync the Gradle project and
run:

```bash
./gradlew test
./gradlew :app:testDebugUnitTest
```

## Non-negotiable data rules

1. Never invent nutrition numbers, barcode values, serving weights, food
   densities, or nutrient units.
2. Never turn an unlogged or partial day into zero calories.
3. Preserve source provenance and the original nutrient profile.
4. Keep barcode data separate from generic foods; AUSNUT/NUTTAB rows have a
   null barcode and use the source food ID as `external_id`.
5. Keep SQL batches at 500 rows or fewer.
6. Keep the service-role Supabase key out of Android. The Android app may use
   only the publishable/anon key and RLS.
7. Treat generated seed SQL as disposable output. Do not commit large real
   food dumps unless explicitly requested.
8. Do not make destructive schema changes without a migration and a clear
   migration note.

## Adaptive-engine rules

* The expenditure loop uses actual logged intake and smoothed weight trend;
  it does not use wearable calorie estimates.
* The reference coverage gate is two consecutive seven-day periods with at
  least six countable nutrition days and one weigh-in per period.
* Missing-data holding is a valid state and must be visible in the UI.
* Recommendations are not punishment, calorie debt, or retroactive make-up
  targets.
* The EWMA alpha, energy-per-kilogram constant, damping cap, BMR starting
  equation, and macro defaults are product parameters. Keep them in a versioned
  configuration object and add tests before changing them.
* Port the Python reference to Kotlin with matching fixture tests before
  adding more coaching behaviour.

## Recommended implementation order

1. Validate and, if necessary, correct the Supabase migration in a disposable
   database. Confirm RLS policies with authenticated and unauthenticated test
   cases.
2. Build the food repository: exact barcode lookup, name/brand search, serving
   scaling, favorites, recent history, custom foods, and recipes.
3. Build the daily logger using snapshot nutrition values so historical logs do
   not change when a source food is edited.
4. Port `adaptive_engine.py` to Kotlin and make the Kotlin tests match the
   Python fixtures.
5. Build weight logging, trend visualisation, expenditure state, and the
   weekly check-in flow.
6. Add barcode camera integration only after exact barcode lookup and manual
   fallback work correctly.
7. Add OCR, URL recipe import, speech, or image AI as separate adapters. They
   must never overwrite a verified food silently.

## Evidence discipline

Use official source material for changing dependency/API details. For claims
about nutrition science or adaptive algorithms, distinguish clearly between:

* direct peer-reviewed evidence;
* adjacent evidence;
* established coaching/product convention; and
* this repository's explicit product choice.

The public MacroFactor documentation is useful for observable behaviour, not
proof that our parameters are correct or identical. Record unresolved gaps in
`docs/` rather than filling them with plausible numbers.

## Git discipline

Native Android boundary: the client must remain a standard Kotlin/Jetpack
Compose Android application built by the `:app` Gradle module. Do not replace
it with React Native, Expo, Flutter, WebView, or a JavaScript bridge. Camera
access must use Android CameraX/ML Kit, and backend access must use the native
Supabase Kotlin client with the publishable/anon key only.

This is a new repository. Keep commits small and intentional. Before handing
back work, report:

* what changed;
* which tests/builds passed;
* which checks could not run and why; and
* any remaining product or evidence gap.
