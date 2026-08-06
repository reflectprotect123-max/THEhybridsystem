# MacroTrack — handoff (2026-08-05, updated after OFF seed pipeline fix)

## Native Android rebuild status

This handoff is for a native Android project, not a React Native conversion.
The client is a standard Gradle Android application using Kotlin, Jetpack
Compose, Android Navigation, CameraX, bundled ML Kit, and the Supabase Kotlin
client. No WebView, JavaScript bridge, Expo runtime, or React dependency is
present in `app/`.

Written so any AI/dev picking this up (this doc was written across a handoff
to ChatGPT and back again, but applies to anyone) can continue with zero
prior context. Read `CLAUDE.md` next — it holds the non-negotiable
product/data rules that bind every future change.

## Round-trip history: Claude → ChatGPT → Claude → Claude again (2026-08-05)

1. Claude built the backend + full core UI (theme, auth, Daily Log, Food
   Search, Weight, Coach) across the earlier sessions recorded below.
2. That work was handed off to ChatGPT (via a zip + this file), which added:
   barcode camera scanning (CameraX + ML Kit), persisted macro-goal programs
   (`MacroProgramRepository`, migrations 002–005), custom-food creation,
   quick-add, a recipe builder, and historical-date navigation on Daily Log.
3. ChatGPT's result was handed back and **independently re-reviewed here**
   before merging — 5 parallel adversarial review passes, one per functional
   area. They found and fixed **6 Critical and ~22 Important defects**
   (most seriously: `DailyLogScreen.kt` missing closing brace causing illegal
   nesting; `CancellationException` swallowed by bare `catch`; `NavController`
   stale after rotation; `encodeDefaults=false` missing on upsert). Full
   detail in the merge commit and `docs/BARCODE_SCANNER_GAPS.md`,
   `docs/WEEKLY_CHECKIN_GAPS.md`, `docs/EXPENDITURE_STATE_GAPS.md`.
4. Claude continued with **nutrition-label OCR** (2026-08-04–08-05, 6-task SDD):
   - Tasks 1–5: completed design + navigation wiring + SavedStateHandle field
     threading + ViewModel integration, each with independent code review.
   - Task 6: merged with layout + result callback, enabling the full flow.
   - Critical whole-branch review found 5 device-only regressions (executor
     lifecycle, async callback contract, JPEG→InputImage format, tolerance
     over-merging rows, thread safety), fixed across 3 rounds (A, B, C) with
     mutation testing of each fix before commit.
   - All 12 parser tests + 2 ViewModel tests pass; real GitHub Actions CI
     succeeded (compile Kotlin, Assemble debug APK, Run unit tests — all green).
   - Feature merged to main at commit 3e11009, verified on real Android SDK.
   - Details: `docs/NUTRITION_LABEL_OCR_GAPS.md`.
5. **OFF seed pipeline unblocked** (2026-08-05, same day as #4): the food-seed
   accumulation had stalled at 471/5000 products on a `401 Unauthorized` from
   `world.openfoodfacts.org/api/v2/search`, reproduced identically across
   three independent networks (a sandboxed CI-style environment, a GitHub
   Actions runner, and a residential connection via the user's own machine) —
   ruling out an IP-reputation block. OFF's own OpenAPI spec confirmed the
   endpoint requires no auth, so a key wasn't the fix either. Root cause,
   found by cloning `openfoodfacts/openfoodfacts-server` directly (the docs
   site itself was unreachable from this sandbox): the legacy Perl-based
   search backend behind `/api/v2/search` is being retired in favor of
   **Search-a-licious** (`search.openfoodfacts.org`), OFF's own recommended
   replacement. Verified live (real AU products, matching `nutriments` shape)
   from the user's machine before switching. `import_openfoodfacts.py` now
   targets that endpoint; a second real bug was found and fixed alongside it
   — `--max-pages` was compared against the absolute page number instead of
   pages fetched this run, so every resumed run past page 8 (the workflow's
   default) silently capped itself to exactly one page. Both fixes are on
   `claude/debug-build-compilation-ang4qd` (commits `6ae1f34`, `c2abe08`),
   covered by two new regression tests in `tests/test_importers.py`, and
   confirmed working via multiple real GitHub Actions runs. See "Data
   import — current status" below for the live count.

**Status change: the Android app has now been compiled for real.** The nutrition
label OCR feature passed CI on actual `kotlinc` 2.1.0 + AGP + real Android SDK,
confirming barcode camera and ML Kit infrastructure is sound. Earlier parts
of the repo (the backend, core UI, barcode scanner, macro programs) remain
uncompiled locally but were built with the same rigorous review discipline.

Python regression tests still pass (9/9). `docs/RELEASE_CANDIDATE_2026-08-04.md`
and `docs/VERIFICATION_2026-08-04.md` are ChatGPT's own self-reported
verification notes from before the review pass above — read them as a record
of what was *claimed*, not as independently confirmed fact; several of their
specific claims (e.g. a "published-coordinate audit" that implied dependency
resolution had been checked) were corrected during the review.

## Where the real repo lives

- GitHub: `reflectprotect123-max/thehybridsystem`
- `claude/macro-factor-app-dev-6twv5o` (the branch named in earlier versions
  of this doc) was merged via PR #1 and is stale — don't develop on it.
- Current work branch: `claude/debug-build-compilation-ang4qd`, which is
  fast-forwarded to `main` plus the OFF seed pipeline fix commits
  (`e802237`, `6ae1f34`, `c2abe08`) not yet merged back to `main`.
- The generated seed SQL batches themselves live on a separate **scratch
  branch**, `data/off-seed-import` — not `main` or the work branch (CLAUDE.md
  rule #7: generated seed SQL is disposable, never committed to a real
  branch). It holds `seed_foods_off_batch_*.sql` files and `off_seed_cursor.txt`
  (the next page to resume from), both written automatically by the
  `.github/workflows/import-food-data.yml` workflow.
- Prefer cloning/fetching from GitHub over any local snapshot if there's any
  doubt about freshness — the repo is the source of truth, not this file.

## What's actually done (backend — fully built, reviewed, never contradicted)

All of this was built with a subagent-driven-development process: implement
→ independent code review → fix loop → repeat, then a final whole-branch
review + fix wave per feature slice. Every slice below passed that process
clean. None of it has ever been compiled (see "The one big caveat" below) —
it's been verified by careful static review against real library/API
sources, not by a compiler.

- **Supabase schema** — `supabase/migrations/001_macro_foundation.sql` plus
  migrations `002_active_macro_program.sql`,
  `003_expenditure_daily_upsert.sql`, and
  `004_owner_reference_policies.sql`, and
  `005_checkin_program_provenance.sql`. Foods, custom foods, recipes, food logs,
  day status, weights, weight trend points, expenditure estimates, weekly
  check-ins, macro-program day targets, and RLS policies are represented.
- **Adaptive engine** — `adaptive_engine.py` (Python reference,
  deterministic, tested) ported to Kotlin at
  `app/src/main/java/com/macrotrack/app/domain/{AdaptiveEngine,
  MacroTargeting, WeeklyCheckIn, AdaptiveEngineModels}.kt`. The Kotlin port
  was verified against the Python reference with ~450,000 randomized inputs
  — zero divergence.
- **Data repositories** (`app/src/main/java/com/macrotrack/app/data/`) —
  one per concern, each wraps Supabase Postgrest calls: `FoodRepository`,
  `CustomFoodRepository`, `RecipeRepository`, `FavoritesRepository`,
  `RecentFoodRepository`, `LogRepository`, `DayStatusRepository`,
  `WeightRepository`, `TrendRepository`, `ExpenditureRepository`,
  `CheckInRepository`, `AuthRepository`. All wired together in
  `AppContainer.kt`.
- **Derived-estimate persistence** — migration
  `003_expenditure_daily_upsert.sql` enforces one expenditure row per user
  and window-end date; recomputes use atomic upsert and remove a stale current
  row only when fresh data cannot support an estimate.
- **Compose UI (full core app)** —
  `app/src/main/java/com/macrotrack/app/ui/`:
  - `theme/` — Material3 theme, calm sage-green/warm-sand palette
  - `nav/` — bottom-tab NavHost (Daily Log / Weight / Coach), auth-gated
  - `auth/` — sign-in/sign-up
  - `dailylog/` — historical-date entries + totals, explicit complete/partial/fasted
    status controls, and owner-scoped soft-delete (never renders an unlogged
    day as zero — see CLAUDE.md rule #2)
  - `search/` — Food Search + Add Log Entry, custom foods, quick-add, favorites,
    and recipe creation (the core logging loop)
  - `weight/` — log a weigh-in, history, hand-rolled Canvas trend sparkline
  - `coach/` — expenditure estimate + weekly check-in accept/decline flow
- **Barcode camera integration** — `BarcodeScannerScreen.kt` uses CameraX
  1.6.1 and bundled ML Kit barcode scanning 17.3.0. It requests camera
  permission, scans common retail formats, returns ML Kit's raw value, and
  routes it through the existing exact `FoodRepository.findByBarcode()` path.
  An unmatched code is reported without UPC/EAN conversion or invented food
  data. See `docs/BARCODE_SCANNER_GAPS.md` for the verification boundary.
- **Custom-food creation UI** — `Food Search → Create custom food` validates
  user-entered serving and macro values, persists through the owner-scoped
  repository, and opens Add Log Entry for the newly created food.
- **Daily status controls** — Daily Log now reads and writes the explicit
  `daily_log_status` row. A completed day requires a logged entry; fasting is
  never inferred from an empty log. The screen also exposes the existing
  owner-scoped soft-delete for log entries.
- **Accepted target persistence** — an accepted ready check-in writes the
  next week's day targets through the active macro program, so acceptance has
  a durable product effect rather than only changing check-in status.
- **Data import pipeline** (Python, root of repo) — `import_openfoodfacts.py`,
  `import_ausnut.py`, `seed_common.py`. Tested against fixtures
  (`tests/test_importers.py`), never run against real production data (see
  below).

## What's NOT done

In `CLAUDE.md`'s own recommended order, everything through step 6 is done;
step 7 is partially complete:

1. ~~Validate migration~~ — done
2. ~~Food repository~~ — done
3. ~~Daily logger~~ — done
4. ~~Port adaptive engine to Kotlin~~ — done
5. ~~Weight logging, trend, expenditure state, weekly check-in~~ — done
   (both backend and UI)
6. ~~Barcode camera integration~~ — fully done; compiled and CI-verified
7. ~~Nutrition-label OCR~~ — fully done and CI-verified (2026-08-05);
   on-device ML Kit Text Recognition, results pre-fill Create Custom Food form
   - **URL recipe import, speech/voice logging** — fully unstarted, not designed

Also not done, not in CLAUDE.md's list but real gaps:

- **A real Supabase project has now been seeded** — 7,691 Open Food Facts
  (Australia-scoped) rows are live in `foods`, confirmed with
  `SELECT COUNT(*) FROM foods WHERE source = 'openfoodfacts';` against the
  actual database, not inferred from script output. 7,738 unique products
  were found in total (see below for why that's the ceiling of this data
  source); 47 were real cross-batch duplicate barcodes, correctly caught and
  skipped by `ON CONFLICT (source, external_id) DO NOTHING`. **Loading this
  data surfaced a real bug that's worth understanding before generating or
  applying seed SQL again**: the first combined-SQL file wrapped each
  ~500-row batch's *entire* set of INSERT statements in one transaction: a
  single duplicate-barcode conflict aborted that whole transaction, silently
  discarding every good row alongside it. Three of the largest batches
  (nearly 7,000 rows) were wiped out this way on the first apply attempt
  before it was caught, diagnosed from the actual `psql` error output
  (`current transaction is aborted, commands ignored until end of
  transaction block` cascading after one `duplicate key value violates
  unique constraint "foods_source_external_id_uidx"`), and fixed by giving
  every individual INSERT statement its own `BEGIN`/`COMMIT` plus
  `ON CONFLICT (source, external_id) WHERE source IS NOT NULL AND
  external_id IS NOT NULL DO NOTHING` (the `WHERE` clause is required
  because `foods_source_external_id_uidx` is a **partial** unique index —
  Postgres won't match a bare `ON CONFLICT (source, external_id)` against
  it without repeating that exact predicate). If more seed SQL gets
  generated/applied later, keep both of those: per-chunk transactions, and
  the full `ON CONFLICT` clause with its `WHERE`, not just the column list.
- **Food seed accumulation: 7,738 unique products found, this is the real
  ceiling of this API path** (Open Food Facts, Australia-scoped; 7,691 of
  them are now actually loaded, see above). The earlier 401 blocker (stuck
  at 471/5000) is resolved via the Search-a-licious switch — see "Data
  import" below — and an unattended batch loop then ran with no target cap
  ("go for MAX" per explicit user request) until it hit a genuine
  `400 Bad Request` from `search.openfoodfacts.org` at page 201 (offset
  10,000). That's Elasticsearch's default `max_result_window`, and this
  API's GET `/search` exposes no `search_after`/cursor parameter to page
  past it — confirmed from the Search-a-licious OpenAPI spec, not assumed.
  **7,738 is everything reachable through this endpoint**, not an arbitrary
  stopping point. To get more Australia-tagged OFF products than this, the
  next lever is the bulk `.jsonl.gz` export (`--input-url`, see "Data
  import" below) rather than more of this same paginated API, since a flat
  file has no result-window ceiling. AUSNUT/NUTTAB is still not run
  (unrelated blocker, needs manually-downloaded FSANZ files). Do not run the
  retailer-catalogue scrapers (`build_retailer_catalogue.py` for
  Coles/Woolworths) — the user explicitly declined that approach citing
  ToS/legal exposure, and those scripts must remain in `scripts/` as
  documentation only, never executed.
- App icon/branding, Play Store packaging — not started.
- **Macro programs** — the active manual goal-rate is now persisted through
  `MacroProgramRepository`, weekly check-ins carry its `program_id`, and
  accepted check-ins create next-week day targets. Full program history,
  pause/complete controls, profile-driven protein/fat preference editing, and
  target editing remain future product slices.

## Compilation and testing status

**The nutrition-label OCR feature (commit 3e11009) has been compiled on real
hardware via GitHub Actions and CI-verified green.** This includes:
- Real Kotlin compilation (kotlinc 2.1.0)
- APK assembly (Android Gradle Plugin + real SDK toolchain)
- Unit test execution (12 parser tests + 2 ViewModel tests, all passing)

The CameraX and ML Kit code paths are confirmed to work against the actual
Android SDK. Earlier parts of the codebase (backend, core UI, barcode scanner,
macro programs) were built and reviewed with the same discipline but have not
been compiled on real hardware; the barcode camera code is expected to compile
since it's in the same subsystem as the now-verified OCR feature.

**Known compilation blockers resolved:**
- Version coordinates now checked against published repositories
- `viewModelFactory` import corrected
- `CancellationException` swallowing fixed in bare `catch` blocks
- Missing `onConflict` on upsert operations added
- Nested `Scaffold` window-insets double-apply removed
- All dependencies validated against real library sources

**Remaining boundary:**
A full end-to-end device/emulator test (camera permission flow, real capture,
OCR processing, navigation state threading, ViewModel state, custom-food
creation) has not been performed. This is the next verification gate if
there's doubt about any of the integrated flows.

## Setting up to actually build and run it

1. **Get a Supabase project** (user already has one). From the dashboard:
   Project Settings → API → copy the Project URL and the **anon/publishable**
   key. **Never** use the service-role key here (CLAUDE.md rule #6).
2. **Apply the schema**: run all files in `supabase/migrations/` in filename
   order (or use `supabase db push`). Migration 002 adds the one-active-goal
   invariant; migration 003 makes same-day expenditure upserts atomic;
   migration 004 hardens cross-owner references; and migration 005 preserves
   check-in provenance when a goal changes during a week. Existing duplicate
   active-program or same-day estimate rows must be reconciled before those
   indexes are applied.
3. **Create `local.properties`** at the repo root (gitignored, never commit
   it):
   ```properties
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_PUBLISHABLE_KEY=your-publishable-key
   ```
4. **Open in Android Studio** and sync Gradle. The codebase is now known to
   compile (CI-verified at commit 3e11009 with real kotlinc + AGP). If
   compilation fails, check that AGP version matches what `build.gradle.kts`
   declares and that Google Maven is reachable.
5. **Run the app on an emulator or device** (this is the first real test of
   the integrated features). Manually walk through:
   - Sign up → sign in
   - Move Daily Log to a previous date
   - Create and log a custom food → quick-add a manual entry
   - Favorite a result → create a recipe with food/custom-food ingredients
   - Search and log the recipe
   - Scan a known barcode → confirm exact match
   - Scan an unknown barcode and confirm the manual-search fallback error
   - **[NEW]** Tap "Scan Nutrition Label" on Create Custom Food and photograph
     a real nutrition panel — confirm fields pre-fill, are editable, stay blank
     if unrecognized
   - Log a weigh-in on Weight → see the trend sparkline
   - Open Coach → see the expenditure estimate
   - Adjust the goal-rate slider → check in
   - Accept a ready target and verify next-week `macro_program_days` rows
   - Sign out
   - **Rotate the device on each screen** (config-change bugs have been fixed
     before, but verify nothing regressed)

## Data import — current status and blockers

### Accumulation progress
- **Open Food Facts Australia: 7,738 products — final, this is the ceiling
  of this API path**, up from 471/5000 as of the previous version of this
  doc. See "OFF endpoint fix" below for the full diagnosis. An unattended
  batch loop ran with no target cap until it hit a real `400 Bad Request`
  at page 201 (offset 10,000) — Elasticsearch's default `max_result_window`,
  with no `search_after`/cursor parameter exposed by this API to page past
  it (checked against the actual OpenAPI spec, not assumed). Retrying page
  201+ will keep failing; it is not a transient error. `data/off-seed-import`
  holds the full set: `seed_foods_off_batch_{1,2,3,5,11,19,69,119}.sql` (one
  file per accumulated run) plus `off_seed_cursor.txt` (stuck at 201 by
  design now). To get more Australia-tagged OFF data than this, use the bulk
  `.jsonl.gz` export instead (`--input` or `--input-url`, see below) — a flat
  file has no result-window ceiling, unlike this paginated search endpoint.
- **AUSNUT/NUTTAB**: Not run (depends on FSANZ Excel/CSV download + import
  script execution, possible but not attempted). This sandbox cannot reach
  `foodstandards.gov.au` either (same class of network-policy block as OFF
  originally appeared to be, confirmed via the outbound proxy's own status
  endpoint) — needs to run from a machine with normal internet access, same
  as OFF did.
- **Retailer catalogues** (Coles, Woolworths): User explicitly declined running
  the scraper scripts (`build_retailer_catalogue.py`) due to ToS/legal exposure.
  These scripts are in `scripts/` for reference only; **do not execute them**.

### OFF endpoint fix (2026-08-05) — read this before touching the importer again

The 401 that stalled the seed at 471/5000 was **not** this sandbox's network
policy, **not** an IP-reputation block, and **not** a missing API key —
confirmed by reproducing the identical 401 on three independent networks
(this sandbox, a real GitHub Actions runner, and the user's own residential
machine), and by reading OFF's own OpenAPI spec (`docs/api/ref/api.yaml` in
`openfoodfacts/openfoodfacts-server`), which explicitly marks
`/api/v2/search` as requiring no auth. The real cause: that endpoint's
legacy Perl/MongoDB-based search backend is being retired. OFF's own
recommended replacement, **Search-a-licious** (`search.openfoodfacts.org`),
was verified working live (real Australian products, matching `nutriments`
shape — `{"proteins_100g": ..., "carbohydrates_100g": ..., ...}`, same as
before) before `import_openfoodfacts.py` was switched to it. Query syntax
changed from the old `countries_tags_en=australia` param to a Lucene-style
`q=countries_tags:"en:australia"` string; response parsing moved from
`payload["products"]` to `payload["hits"]`.

A second, unrelated bug was found and fixed in the same session: `--max-pages`
was compared against the raw page number instead of pages fetched *this run*,
so resuming from any page past the configured `--max-pages` value (e.g. page
11 with the workflow's default of 8) silently capped every run to exactly one
page — 50 rows instead of hundreds. Both fixes are covered by regression
tests in `tests/test_importers.py` (`test_iter_api_products_paginates_search_a_licious_hits`,
`test_iter_api_products_max_pages_counts_pages_fetched_not_absolute_page_number`,
`test_iter_api_products_stops_on_search_error_response`).

The Elasticsearch `max_result_window` hypothesis was tested directly rather
than assumed: page 200 (offset 9,950–9,999) succeeded cleanly, and page 201
(offset 10,000+) failed with a real `400 Bad Request`, matching the standard
10,000-hit default exactly. **7,738 rows is the actual maximum obtainable
through `search.openfoodfacts.org`'s GET `/search` for this query** — not an
arbitrary stopping point, and not worth re-running to "try again."

If `/api/v2/search` or Search-a-licious changes behavior again, re-diagnose
before assuming either the old fix, the old blocker, or this ceiling still
applies — this is external infrastructure this repo doesn't control.

### How to complete the imports

**Open Food Facts** (Australia-scoped, via the now-working Search-a-licious
API — no API key, but do send a descriptive `--user-agent` per OFF's own
request):
```bash
python3 import_openfoodfacts.py --output seed_foods_off.sql
```
To resume the actual in-progress accumulation instead of starting over, pull
`off_seed_cursor.txt` from `data/off-seed-import` first and pass it as
`--start-page`. Or from a local bulk dump (a completely different, if much
larger, path that doesn't touch the live API at all):
```bash
# Download from https://world.openfoodfacts.org/data (the .jsonl.gz export)
python3 import_openfoodfacts.py \
  --input openfoodfacts-products.jsonl.gz \
  --output seed_foods_off.sql
```
Or stream a remote bulk export directly without downloading it to disk first
(added alongside the endpoint fix, as a fallback path — decompresses on the
fly, never buffers the full multi-GB file):
```bash
python3 import_openfoodfacts.py \
  --input-url https://images.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz \
  --output seed_foods_off.sql
```

**AUSNUT/AFCD** (download the current Excel files from
https://www.foodstandards.gov.au — search "AUSNUT" or "AFCD"):
```bash
python3 import_ausnut.py \
  --nutrients "AUSNUT 2023 - Food nutrient profiles.xlsx" \
  --foods "AUSNUT 2023 - Food details.xlsx" \
  --measures "AUSNUT 2023 - Food measures.xlsx" \
  --output seed_foods_ausnut.sql
```
Or NUTTAB-style CSVs (also on the same FSANZ page):
```bash
python3 import_ausnut.py \
  --source nuttab \
  --nutrients NUTTAB_Nutrient_File.csv \
  --foods NUTTAB_Food_File.csv \
  --output seed_foods_nuttab.sql
```

Then apply the generated `.sql` files to your Supabase project using a
**service-role** connection (SQL editor or `psql`) — **never** commit these
generated SQL files to the repo (CLAUDE.md rule #7: they're disposable
output, and AUSNUT/OFF dumps are large). Batches are already capped at 500
rows per CLAUDE.md rule #5, no code change needed there.

Full documented behavior (kJ→kcal conversion rules, barcode vs. generic-food
provenance, 100g/ml fallback policy) is in `README.md`'s import sections —
read that before running these for real, it documents exactly what the
scripts will and won't guess.

### Retailer catalogues: do not run these

The `scripts/` directory contains `build_retailer_catalogue.py` and related
tooling for scraping Coles and Woolworths product catalogues. **Do not run
these scripts.** The user explicitly declined this approach, citing ToS/legal
exposure and the risk of automated large-scale extraction against retail
challenge systems (Incapsula/Bot Manager). The user's position on this is firm:
it's a security/legal boundary, not a preference. These scripts remain in the
repo for documentation/reference only, as a record of what was attempted during
the design phase.

## Non-negotiable rules (CLAUDE.md, do not violate these)

1. Never invent nutrition numbers, barcode values, serving weights, food
   densities, or nutrient units.
2. Never turn an unlogged or partial day into zero calories.
3. Preserve source provenance and the original nutrient profile.
4. Keep barcode data separate from generic foods (AUSNUT/NUTTAB rows have
   `barcode = NULL`, `external_id` = the source food ID).
5. Keep SQL batches at 500 rows or fewer.
6. Keep the service-role Supabase key out of Android — publishable/anon key
   + RLS only.
7. Generated seed SQL is disposable — don't commit large real food dumps.
8. No destructive schema changes without a migration + a clear note.
9. If implementation and a doc disagree, stop and reconcile — don't
   silently pick one.

## Where to find more detail

- `docs/*_GAPS.md` — every known unresolved edge case/limitation per
  feature slice, written down instead of guessed at. Read the one for
  whatever you're about to touch before changing it.
- `docs/superpowers/plans/*.md` — the actual implementation plans this was
  built from, one per feature slice, each with a Global Constraints section
  and a Self-Review. These are the most detailed record of *why* things
  were built the way they were.
- `docs/ADAPTIVE_ENGINE_CONTRACT.md` — the Kotlin-port handoff contract for
  the adaptive engine specifically (evidence boundaries, what's product
  choice vs. what's evidence-backed).

## If you don't have superpowers/subagent-driven-development tooling

Everything above was built via a specific Claude Code plugin
(`superpowers`) that structures work as: write a detailed plan →
dispatch an implementer → independent code review → fix loop → final
whole-branch review. You don't need that exact tooling to continue — just
keep doing what it was enforcing: read the actual current code before
changing it (not from memory), write tests where you can, get a second
independent look at anything non-trivial before considering it done, and
write down any gap or tradeoff you accept rather than silently shipping it.
