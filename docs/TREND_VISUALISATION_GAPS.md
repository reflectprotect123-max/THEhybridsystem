# Trend visualisation — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From the final whole-branch review of
`docs/superpowers/plans/2026-08-03-trend-visualisation.md` (branch
`claude/macro-factor-app-dev-6twv5o`). The review found one Critical and four
Important findings; the Critical and two of the Important findings were
fixed directly (see below); the remaining two are recorded here because
fixing them correctly is a product decision, not a code defect with one
obvious right answer.

## Fixed during final review (for context, not a live gap)

- **Critical:** `TrendRepository` used `LocalDate.ofInstant(Instant, ZoneId)`,
  a Java 9 `java.time` addition Android only ships from API 34. This
  module's `minSdk` is 26 with no core-library desugaring, so this would
  have thrown `NoSuchMethodError` at runtime on API 26-33 devices. Fixed by
  switching to `instant.atZone(zoneId).toLocalDate()` — the same API-26-safe
  idiom `WeightTrendCalculator` already used.
- **Important:** `recomputeTrend` seeded the EWMA from `LocalDate(since)`
  instead of the user's actual earliest weigh-in, so the persisted trend
  value for a given `(user_id, trend_date)` depended on whatever `since` the
  last caller happened to pass — not deterministic. Fixed by always fetching
  full history (`weightRepository.listEntries(Instant.EPOCH)`) to seed the
  EWMA correctly, and using `since` only to decide which computed days get
  persisted/returned — the same pattern `AdaptiveEngine.estimateExpenditure`
  already uses (compute over full history, filter to a window after).
- **Important:** deleting a weigh-in left stale rows in `weight_trend_points`
  untouched — `weight-logging`'s own gaps doc assigned this exactly to this
  slice ("whoever builds the trend slice owns that"). First fix attempt used
  `filterNot("trend_date", FilterOperator.IN, survivingDates)` alongside
  `gte`/`lte` on the same column inside one `filter { }` block — a scoped
  re-review caught that postgrest-kt's top-level request params are keyed by
  column and folded to their **first** value only
  (`Utils.kt`'s `mapToFirstValue`), so two of those three conditions were
  silently dropped and the delete executed as an unbounded
  `trend_date >= since`, resetting `created_at` on every unchanged row on
  every recompute and opening a non-atomic delete-then-reinsert window over
  the user's entire trend history. Corrected by exploiting a property of the
  series itself: once `AdaptiveEngine.weightTrend` sees a first non-null
  input it never re-emits `null`, so a non-empty `payload` is always a
  contiguous run from `max(earliestDay, requestedStart)` through `today` —
  the only rows that can be stale are a leading gap before `payload`'s first
  date, or (if `payload` is empty) the whole window. The delete now targets
  exactly that range, grouping its two `trend_date` bounds inside `and { }`
  (verified against the real 3.7.0 sources: `and { }`'s inner params are
  joined into one string rather than folded to a first value, so both bounds
  survive), and is skipped entirely in the common case where nothing is
  orphaned — so an unchanged row's `created_at` is preserved via ordinary
  `ON CONFLICT DO UPDATE` upsert semantics instead of being churned through a
  delete-then-insert.

## `source_window_days` does not correspond to anything the code computes

`weight_trend_points.source_window_days` (schema default `14`,
`NewTrendPoint.sourceWindowDays` default `14`) reads like it names a
rolling-window length, but the computation is an unbounded EWMA (α = 0.20)
that carries forward indefinitely — it has no finite window. The EWMA's
actual characterisations are half-life ≈ 3.1 days, mean lag `(1-α)/α = 4`
days, effective span `2/α - 1 = 9` days — none of them 14. The `14` most
likely traces back to `EngineConfig.minimumHistoryDays` (a different,
unrelated concept: the expenditure engine's minimum-history gate), copied
into the trend schema's default without being re-derived for this
computation.

It also never round-trips: this app's default `Json` serializer has
`encodeDefaults = false`, and `PostgrestQueryBuilder.upsert` derives its
`columns` param from whatever keys are actually present in the serialized
body — so a `NewTrendPoint` built with the default `sourceWindowDays = 14`
never transmits that field at all, and every row's stored value is really
just the Postgres column default, not anything this code decided or wrote.

This isn't fixed here because there's no correct number to put in its
place — the computation genuinely has no window, so any specific value
would be an equally invented one (the same failure mode CLAUDE.md's
evidence-discipline rule warns against). The real fix is a product decision:
either drop/nullable the column via a migration, or give it real meaning
(e.g. the actual day-count of the fetched history, or a fixed EWMA lookback
this slice doesn't currently have) and set it explicitly at write time.

Related, same root cause: `method = "ewma_reference"` names the method but
not its parameter — α isn't recorded anywhere on the row. If
`EngineConfig.trendAlpha` ever changes, existing persisted rows are
indistinguishable from rows computed under the new value, and nothing
prompts a recompute. Worth deciding alongside the above: a version-suffixed
`method` (`ewma_reference_v1`) or a stored `alpha` column.

## `recomputeTrend`'s delete-then-upsert is two requests, not one transaction

The stale-row cleanup delete and the batched upserts are separate HTTP
requests; postgrest-kt has no multi-statement transaction primitive to wrap
them in. A crash between the two leaves the delete applied without the
upsert. This is far less dangerous than it would have been with the first
fix attempt above — the delete now only ever removes rows already
determined to be orphaned (no current source data supports them), never
rows the upsert is about to rewrite with a fresh value — so a crash in that
window loses nothing that wasn't already gone from the source data's
perspective. Still worth recording: a true transaction (e.g. a Postgres
function called via RPC) would close this window entirely, and hasn't been
attempted here.

`recomputeTrend` also now fetches full history unconditionally
(`weightRepository.listEntries(Instant.EPOCH)`) to seed the EWMA correctly,
rather than only fetching from `since`. `WeightRepository.listEntries` has
no row limit and orders ascending (a gap already recorded in
`WEIGHT_LOGGING_GAPS.md`); fetching full history on every recompute makes
that existing gap's failure mode — a project-level PostgREST row cap
silently truncating the *newest* rows — more likely to actually bite than
it was when only a bounded `since`-forward range was fetched.

## Same-day combination is a fixed function, not a configurable parameter

`WeightTrendCalculator.averageByLocalDay` averages same-day duplicates —
the standard convention, and now correctly a named, tested function (fixed
during Task 1's review) rather than inlined arithmetic. `alpha` is likewise
now sourced from `EngineConfig.trendAlpha` by default rather than a second
hardcoded literal (fixed during final review), so a future change to the
adaptive engine's α doesn't silently diverge from the trend slice's own
value.

What's still open: the *choice* of averaging (mean vs. first vs. last) is
hardcoded to mean, with no way to express a different convention without
editing code. CLAUDE.md's adaptive-engine rules ask that product parameters
like this "keep them in a versioned configuration object... before changing
[them]" — mean is very likely the right default and probably needs no
change, but there's currently no config seam to change it through if a
future product decision calls for one. Not fixing this now since there's no
evidence today's default is wrong — recording it so a future change doesn't
have to rediscover that the only way to alter it is editing
`WeightTrendCalculator` directly.

## No trigger connects a weigh-in write/delete to a trend recompute

`recomputeTrend` is entirely caller-driven — nothing in this slice schedules
it. Today, nothing calls it at all (no UI/ViewModel exists yet). Whoever
wires up the first caller needs to decide when recomputation happens: after
every `WeightRepository.logWeight`/`deleteEntry` call, on a schedule, or
on-demand when a trend screen opens. A DB trigger is also an option but
isn't attempted here — this slice is data-layer only.

## Precision: persisted trend values are display-rounded; in-engine values aren't

`recomputeTrend` applies `round1` (0.1 kg) before persisting. `AdaptiveEngine`
computes its own trend from raw `DailyRecord.weightKg` inputs, unrounded, and
never reads `weight_trend_points` — so nothing is broken today. But
`WeightTrendCalculator.dailyTrend`'s raw (unrounded) output has the same
shape (`List<Pair<LocalDate, Double?>>`) that `AdaptiveEngine.linearSlope`
consumes, which makes reading `listTrendPoints` and slaping a slope onto it
look like an equivalent, cheaper alternative to `estimateExpenditure`'s own
computation. It isn't: over a 14-day window, a ±0.05 kg rounding error at
each endpoint can shift the fitted slope by roughly 0.007 kg/day, which at
7700 kcal/kg is around 55 kcal/day — more than half of
`EngineConfig.maximumExpenditureStepKcal`'s 100 kcal/day damping cap. A
future expenditure-state slice must keep computing its own trend from raw
weigh-ins, not from this table's rounded values.

## Timezone is captured per call, not stored

`recomputeTrend`/`listTrendPoints` bucket by `ZoneId.systemDefault()` at call
time. Nothing on `weight_trend_points` records which zone produced a given
row. A user who travels across timezones and later triggers a recompute
will have some days re-bucketed under the new zone, silently rewriting
already-persisted trend history for those days. Not fixed here — no evidence
yet of how much this matters in practice, and doing so would need either a
stored zone column or a documented single-zone-per-account policy.

## `minSdk`/`java.time` policy is not enforced anywhere

The Critical finding above (`LocalDate.ofInstant` requiring API 34 against
`minSdk = 26`) was caught by manual review, not by tooling — there's no
Android Lint configuration in `app/build.gradle.kts` that would have flagged
a `NewApi` violation automatically. The project should explicitly decide:
stay on the API-26 `java.time` subset (and add lint enforcement for it), or
enable `coreLibraryDesugaring` and gain the newer methods. Neither decision
is made today; this file exists as a placeholder recording that the decision
is still open, since the alternative is repeating this exact review finding
on the next `java.time` call someone reaches for.
