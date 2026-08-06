# Macro+

Native Android application for nutrition tracking, built with Kotlin and
Jetpack Compose against a Supabase/Postgres backend. There is no React Native,
Flutter, WebView, or JavaScript runtime in the Android client. The current
vertical slice includes Australian food imports, provenance-aware food
records, logging/recipe tables, weight trends, expenditure estimates, macro
programs, weekly check-ins, and a CameraX/ML Kit barcode scanner.

This is not a finished MacroFactor clone. The public product behaviour is
replicated as a deterministic contract where it is visible; private constants
and exact implementation details are not guessed.

## Repository layout

```text
supabase/migrations/001_macro_foundation.sql  Supabase schema and RLS
app/                                         Native Android/Kotlin/Compose module
import_openfoodfacts.py                       OFF API/dump importer
import_ausnut.py                              AUSNUT/NUTTAB importer
seed_common.py                                Shared SQL/parsing helpers
adaptive_engine.py                            Deterministic Python reference
tests/                                         Offline regression tests
examples/checkin.json                         Engine CLI example
```

## Requirements

* Python 3.10 or newer.
* `requests` only when using Open Food Facts API mode.
* No pandas or Excel package is required. XLSX files are read with the Python
  standard library. Binary `.xls` files must be saved as CSV/TSV/XLSX first.
* Android Studio with the Android 16/API 36 SDK for the Android module.
  (`compileSdk`/`targetSdk` were originally set to the not-yet-released API 37;
  confirmed against Google's published platform list on 2026-08-04 that API 37
  is not yet available to install, so the module currently targets the latest
  real, installable platform, API 36. Bump back to 37 once Google publishes
  it.)

```bash
cd /workspace/macroplus
python3 -m venv .venv
source .venv/bin/activate
python -m pip install requests
```

The native Android module reads `SUPABASE_URL` and
`SUPABASE_PUBLISHABLE_KEY` from a local `local.properties` file. That file is
ignored by Git and must never contain a service-role key.

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=your-publishable-key
```

Open the repository in Android Studio and sync the Gradle project. The native
module uses the Compose compiler plugin, Kotlin 2.3.21, Compose BOM 2026.06.00,
AGP 9.3.0, Gradle 9.5.1, compile/target SDK 37, lifecycle-runtime-compose
2.10.0, CameraX 1.6.1, bundled ML Kit barcode scanning 17.3.0, and Supabase
Kotlin BOM 3.7.0. These coordinates were checked against their published
repositories on 2026-08-04; the first real Android sync/build still needs to
run on a machine with the Android SDK and Java dependency access.

## Database setup

Apply all migrations through the Supabase CLI or your normal migration runner
(`002_active_macro_program.sql` adds the one-active-goal invariant,
`003_expenditure_daily_upsert.sql` makes same-day derived estimates atomic,
`004_owner_reference_policies.sql` prevents cross-user custom-food and recipe
references, and `005_checkin_program_provenance.sql` keeps same-week
check-ins separate when a goal changes):

```bash
supabase db push
```

The migration preserves the original `foods` columns and adds micronutrient
JSON, ingredients/allergens, source provenance, custom foods, recipes, food
logs, explicit incomplete/fasted day state, weights, trend points, macro
programs, expenditure estimates, check-ins, nutrient targets, measurements,
photos, and row-level security policies.

Use a service-role/admin connection for the bulk seed SQL. Do not expose that
credential in the Android client.

## Open Food Facts import

API mode filters for Australia and then post-filters for the Australian country
tag. It retries transient HTTP responses and writes batches of at most 500
rows:

```bash
python import_openfoodfacts.py \
  --output seed_foods_off.sql
```

For a reproducible local dump instead of the API:

```bash
python import_openfoodfacts.py \
  --input openfoodfacts-products.jsonl.gz \
  --output seed_foods_off.sql
```

To retain compatibility with the original eleven-column table before applying
the migration:

```bash
python import_openfoodfacts.py \
  --input openfoodfacts-products.jsonl.gz \
  --legacy-schema \
  --output seed_foods_off_legacy.sql
```

The importer skips missing barcode/name/macro records, converts kJ to kcal
only when kcal is absent and a kJ value is present, and refuses to invent a
gram↔millilitre density conversion. It also stores numeric source nutrients in
`foods.nutrients` and records a quality flag for fallbacks/conversions.

## AUSNUT, AFCD, or NUTTAB import

Current FSANZ download pages publish Excel files for food details, nutrient
profiles, and food measures. The importer also accepts the older AUSNUT
2011–13/NUTTAB layouts when their headers are present.

Example with AUSNUT-style files:

```bash
python import_ausnut.py \
  --nutrients "AUSNUT 2023 - Food nutrient profiles.xlsx" \
  --foods "AUSNUT 2023 - Food details.xlsx" \
  --measures "AUSNUT 2023 - Food measures.xlsx" \
  --output seed_foods_ausnut.sql
```

For a NUTTAB-style long nutrient table:

```bash
python import_ausnut.py \
  --source nuttab \
  --nutrients NUTTAB_Nutrient_File.csv \
  --foods NUTTAB_Food_File.csv \
  --output seed_foods_nuttab.sql
```

Generic foods have `barcode = NULL` and use the FSANZ food ID as
`external_id`. The importer writes a 100 g/ml fallback when no compatible
measure is available, not a guessed household-unit weight.

## Deterministic adaptive engine

The engine is a Python reference implementation to port into Kotlin. It uses:

1. smoothed weight trend rather than raw scale noise;
2. logged intake minus the energy represented by the trend slope;
3. explicit coverage gates (two consecutive seven-day periods with adequate
   nutrition and weight data);
4. holding states for incomplete data;
5. damped expenditure updates; and
6. signed goal rates for loss, maintenance, or gain, followed by configurable
   protein/fat preferences and carbohydrate allocation.

Run the example:

```bash
python adaptive_engine.py --input examples/checkin.json
```

The `EngineConfig` values are product parameters, not claims that they are
MacroFactor's private values. Keep them configurable and log the explanation
and inputs with each check-in so the Kotlin client can show a human-readable
decision trail.

## Tests

```bash
python -m unittest discover -s tests -v
python3 -m py_compile *.py
```

Native Android verification:

```bash
./scripts/verify_native_source.sh
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Honest feature boundary

This repository now contains the backend/data foundation, deterministic
decision logic, native Compose screens, historical-date logging, explicit daily
status controls, owner-scoped log deletion, custom-food and quick-add logging,
recipe creation, favorites, a persisted active macro goal, accepted check-in
targets for the next program week, an initial native camera barcode scanner,
and a native-camera nutrition-label OCR scanner (macros only, on the Create
Custom Food screen). The following remain intentionally incomplete
client/product work: URL recipe extraction, photo/voice AI logging, offline
sync conflict resolution, notifications, billing, full macro-program history
editing, and body-metrics/progress-photo screens. Both camera scanners still
need a real Android build and physical-device verification; see
`docs/BARCODE_SCANNER_GAPS.md` and `docs/NUTRITION_LABEL_OCR_GAPS.md`.

The public MacroFactor documentation describes deterministic intake/weight
adaptation, weekly check-ins, barcode/search/recipe logging, micronutrients,
body metrics, and progress photos. It does not publish enough detail to claim
that this repository has identical algorithms or identical UI behaviour.
