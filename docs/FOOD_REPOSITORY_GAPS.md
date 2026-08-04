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

## Resolved: micronutrients are now exposed through the food models

`Food.nutrients` and `CustomFood.nutrients` now decode the JSONB source profile
as a `JsonObject`, so callers can reach the original nutrient profile without
confusing it with the serving-scaled macro columns.

The importer stores `nutrients` **unscaled**
(per `nutrition_basis_qty`/`nutrition_basis_unit`), while
`calories`/`protein_g`/`carbs_g`/`fat_g` are scaled to `serving_qty`/
`serving_unit` — the two use different denominators. Don't apply one
multiplier to both. The current logger preserves that source denominator and
records the logged macro scaling separately; it does not invent scaled
micronutrient values.

## Resolved: daily-logger now populates the log-entry snapshot

From the final whole-branch review of
`docs/superpowers/plans/2026-08-03-daily-logger.md` (the daily-logger slice
built on top of this repository layer).

`LogRepository` now copies the source nutrient object and writes a structured
`source_snapshot` for foods, custom foods, recipes, and quick-add entries.
The snapshot includes the source identity, serving/basis units, logged
quantity, and the resulting logged macros. It is created before the insert and
is never re-derived from the referenced food on read. Recipe snapshots record
the recipe identity and resolved logged macros; ingredient-level micronutrient
aggregation is deliberately not invented because the current resolver only
returns macros.
