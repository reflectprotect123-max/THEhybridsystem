# Food repository — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From the final whole-branch review of
`docs/superpowers/plans/2026-08-03-food-repository.md` (branch
`claude/macro-factor-app-dev-6twv5o`).

## Needs live verification before trusting special-character search

`FoodRepository.search()` combines `SearchPatterns.ilikePattern()`'s
backslash-escaping with Postgrest's `or { ilike(...); ilike(...) }`, which
applies its own escaping when a filter sits inside a logical (`or`/`and`)
expression (`FilterOperation.escapedValue`). Reading the two escaping layers
side by side does not settle whether they compose correctly or double-escape
— this needs a real PostgREST endpoint, not more static analysis. Before
relying on this in production, run these four queries against a real
Supabase project and confirm each returns the expected row and no others:

- `100% whole wheat` (unquoted escaping path)
- `greek_yogurt`
- `2.5% milk` (forces Postgrest's quoted-value path, since `.` is in
  `quotedCharacters`)
- `Coca-Cola, Zero` (forces quoting via `,`)

## Micronutrients not exposed through this layer yet

`foods.nutrients` / `custom_foods.nutrients` (jsonb) are not modelled in
`Food`/`CustomFood` (`app/src/main/java/com/macrotrack/app/data/model/`).
CLAUDE.md's rule #3 ("preserve source provenance and the original nutrient
profile") is only partly satisfied — `source`/`external_id` provenance is
preserved, but the micronutrient profile itself isn't reachable through the
repository layer. Nothing is lost in the database; this is a read-side gap.

Needed before the daily-logger slice snapshots a log entry's nutrients.
Whoever adds it: `import_openfoodfacts.py` stores `nutrients` **unscaled**
(per `nutrition_basis_qty`/`nutrition_basis_unit`), while
`calories`/`protein_g`/`carbs_g`/`fat_g` are scaled to `serving_qty`/
`serving_unit` — the two use different denominators. Don't apply one
multiplier to both when building the nutrient-scaling logic.

## Write-side gap: daily-logger slice never populates the log-entry snapshot

From the final whole-branch review of
`docs/superpowers/plans/2026-08-03-daily-logger.md` (the daily-logger slice
built on top of this repository layer).

`food_log_entries.nutrients` / `food_log_entries.source_snapshot` (jsonb) are
never populated by `LogRepository`. `NewFoodLogEntry`
(`app/src/main/java/com/macrotrack/app/data/model/LogEntryModels.kt`) only
carries `calories`/`protein_g`/`carbs_g`/`fat_g`/`display_name` as its
snapshot fields — there is no `nutrients` or `source_snapshot` property on
the payload at all, so every row this slice inserts leaves those two jsonb
columns at their `'{}'::jsonb` default, permanently.

This compounds with the read-side gap above: because `nutrients`/
`source_snapshot` are the *sole* historical record for a log entry (per
CLAUDE.md rules #2/#3, a snapshot is never re-derived from `food_id` after
insert — that's the entire point of snapshotting), any entry logged while
this gap is open can never be honestly backfilled later. A backfill would
have to look up the current `foods`/`custom_foods` row for that
`food_id`/`custom_food_id`, which is exactly the re-derivation rule #2/#3
forbid, and would silently misattribute a food's *current* nutrient profile
to a historical log entry that may have been logged against an older
version of that food's data.

This blocks nothing in the current UI-less state — no caller reads
`nutrients`/`source_snapshot` back yet. But it must land before, or
alongside, whatever work adds `nutrients` to the `Food`/`CustomFood` models
(the read-side gap immediately above), since that's the point at which a
caller would first have real nutrient data available to put into the
snapshot at write time. Landing the read-side fix without this one would
just move the gap one layer up instead of closing it.
