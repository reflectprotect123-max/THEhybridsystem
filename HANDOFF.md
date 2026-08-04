# MacroTrack — handoff (2026-08-04, updated)

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

## Round-trip history: Claude → ChatGPT → Claude (review pass)

1. Claude built the backend + full core UI (theme, auth, Daily Log, Food
   Search, Weight, Coach) across the earlier sessions recorded below.
2. That work was handed off to ChatGPT (via a zip + this file), which added:
   barcode camera scanning (CameraX + ML Kit), persisted macro-goal programs
   (`MacroProgramRepository`, migrations 002–005), custom-food creation,
   quick-add, a recipe builder, and historical-date navigation on Daily Log.
3. ChatGPT's result was handed back and **independently re-reviewed here**
   before merging — 5 parallel adversarial review passes, one per functional
   area, none of which trusted ChatGPT's own self-reported verification
   claims. They found **6 Critical and ~22 Important defects**, most
   seriously: `DailyLogScreen.kt` did not compile at all (a missing closing
   brace made a `private fun` illegally nested inside another function —
   caught by careful manual brace-counting, since no compiler was available
   here either). Other Criticals included two independent recurrences of bug
   *classes* already fixed once earlier in this project (a `CancellationException`
   dead-end reintroduced through a new code path; a `NavController` captured
   inside a retained `ViewModel`'s constructor, stale after rotation; the
   `encodeDefaults=false`-omission-on-upsert bug reappearing in a new model
   class after a migration changed insert→upsert). All were fixed in 5
   parallel fix passes on disjoint files, then independently re-verified
   (brace balance confirmed via hand trace, a purpose-built lexer, *and* a
   negative-control reproduction of the original bug) before committing.
   Full detail: the commit message on the merge commit, and
   `docs/BARCODE_SCANNER_GAPS.md`, `docs/WEEKLY_CHECKIN_GAPS.md`,
   `docs/EXPENDITURE_STATE_GAPS.md` (each gained new sections from this pass).

**Still true after all of that: nothing in this repository has ever been
compiled by a real Kotlin compiler.** See "The one big caveat" below — this
sandbox's network policy still blocks `dl.google.com` (re-confirmed by a
direct `curl`/`./gradlew` test after the ChatGPT round-trip, not assumed from
earlier notes), the same wall both Claude and ChatGPT hit independently.

Python regression tests still pass (9/9). `docs/RELEASE_CANDIDATE_2026-08-04.md`
and `docs/VERIFICATION_2026-08-04.md` are ChatGPT's own self-reported
verification notes from before the review pass above — read them as a record
of what was *claimed*, not as independently confirmed fact; several of their
specific claims (e.g. a "published-coordinate audit" that implied dependency
resolution had been checked) were corrected during the review.

## Where the real repo lives

- GitHub: `reflectprotect123-max/thehybridsystem`
- Branch: `claude/macro-factor-app-dev-6twv5o` (everything below is on this
  branch, fully pushed)
- Open PR: **#1** — https://github.com/reflectprotect123-max/THEhybridsystem/pull/1
  (base branch `main`, created against the project's actual root commit —
  see the PR description for why a `main` branch had to be created)
- This zip is a point-in-time snapshot of that branch. **Prefer cloning from
  GitHub over unzipping** if there's any doubt about freshness — the repo is
  the source of truth, not this file.

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
step 7 is not started:

1. ~~Validate migration~~ — done
2. ~~Food repository~~ — done
3. ~~Daily logger~~ — done
4. ~~Port adaptive engine to Kotlin~~ — done
5. ~~Weight logging, trend, expenditure state, weekly check-in~~ — done
   (both backend and UI)
6. ~~Barcode camera integration~~ — implemented and independently reviewed
   (see the round-trip history above), but not yet compiled or tested on a
   physical Android device.
7. **OCR / URL recipe import / speech / image AI adapters** — design
   in progress for the first of these (nutrition-label OCR, picked over
   URL-recipe-import and speech logging as the smallest incremental step —
   it reuses the CameraX capture infrastructure just built for barcode
   scanning). As of this handoff: a design has been proposed and presented
   to the user (on-device ML Kit Text Recognition, not a cloud OCR API;
   results pre-fill the *existing* Create Custom Food form rather than a new
   save pathway; every extracted field stays fully editable and a low-confidence
   field is left blank rather than guessed) but **not yet formally
   written up as a spec, not yet approved, and no implementation plan or
   code exists for it**. Whoever picks this up next should either continue
   that design conversation to a written, approved spec before building, or
   restart the design from scratch if the context feels stale. URL recipe
   import and speech/voice logging are fully unstarted, not even designed.

Also not done, not in CLAUDE.md's list but real gaps:

- **The Android module has never been compiled, anywhere, ever.** The new
  CameraX/ML Kit slice is included in this caveat. See below
  — this is the single most important unresolved risk in the whole repo.
- **No real Supabase project has been created or seeded** — schema and
  import pipeline are ready, nothing has actually been run against a live
  database.
- **No real AUSNUT/NUTTAB or OpenFoodFacts import has been run** — see
  "Data import" below, this needs an environment with real internet access.
- App icon/branding, Play Store packaging — not started.
- **Macro programs** — the active manual goal-rate is now persisted through
  `MacroProgramRepository`, weekly check-ins carry its `program_id`, and
  accepted check-ins create next-week day targets. Full program history,
  pause/complete controls, profile-driven protein/fat preference editing, and
  target editing remain future product slices.

## The one big caveat — nothing has ever compiled

The sandbox this was built in has **no Android SDK, no emulator, and no
network access to Google's Maven** (`dl.google.com` is blocked by its proxy
policy). Every Kotlin file was verified by:
1. Reading the actual current source of every file it calls into (never
   assuming a signature from memory)
2. Cross-checking third-party API usage (Supabase auth-kt/postgrest-kt,
   AndroidX Navigation-Compose, Jetpack lifecycle-viewmodel) against real
   decompiled/downloaded library sources where possible
3. Multiple independent review passes per feature slice, including a final
   whole-branch review on the most capable model available, specifically
   hunting for exactly this class of "looks right but doesn't compile" bug

This caught and fixed several real bugs this way (a wrong import package
for `viewModelFactory`, `CancellationException` being silently swallowed by
bare `catch (Exception)` blocks across every ViewModel, a missing
`onConflict` on an upsert that would throw on the second write, a nested
`Scaffold` double-applying window insets). **But it is still static review,
not a compiler.** The very first thing that must happen on a real machine:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

The previous version-coordinate and icon-pack warnings are closed: the
coordinates are now checked against the published repositories, and the UI
uses text-based navigation/FAB affordances instead of relying on a transitive
Material icon dependency. The remaining build boundary is environmental:
this workspace has no Android SDK and Java cannot reach Google Maven, so a
real Android compile, APK, emulator run, and physical-device camera test must
be done by Claude or Android Studio.

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
4. **Open in Android Studio**, sync Gradle, then run the two commands in
   "The one big caveat" above. Fix whatever the compiler finds — expect a
   handful of real issues given nothing here has compiled before.
5. **Manually walk the app** once it builds: sign up → sign in → move Daily
   Log to a previous date → create and log a custom food → quick-add a manual
   entry → favorite a result → create a recipe with food/custom-food
   ingredients → search and log the recipe → scan a known barcode → confirm
   exact match → scan an unknown barcode and confirm the manual-search error →
   log a weigh-in on Weight → see the trend sparkline → open Coach → see the
   expenditure estimate → adjust the goal-rate slider → check in → accept a
   ready target and verify next-week `macro_program_days` rows → sign out.
   Rotate the device on each screen (there were real config-change bugs
   already found and fixed once — check nothing regressed).

## Data import — why it wasn't run, and exactly how to run it

The sandbox this was built in blocks outbound HTTPS to arbitrary domains
(only a small allowlist of package-registry/dev-infra domains is reachable
— `pypi.org`, `npmjs.org`, etc.). Both FSANZ (AUSNUT/NUTTAB) and
OpenFoodFacts returned `403` on every connection attempt. **The import
scripts themselves are ready and tested against fixtures** — they just need
to run somewhere with real internet access (your own machine is fine).

**Open Food Facts** (Australia-scoped, via live API):
```bash
python3 import_openfoodfacts.py --output seed_foods_off.sql
```
Or from a local dump (more reproducible, avoids hammering their API):
```bash
# Download from https://world.openfoodfacts.org/data (the .jsonl.gz export)
python3 import_openfoodfacts.py \
  --input openfoodfacts-products.jsonl.gz \
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
