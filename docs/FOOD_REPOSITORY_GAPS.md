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
