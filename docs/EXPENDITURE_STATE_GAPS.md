# Expenditure state — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From building
`docs/superpowers/plans/2026-08-03-expenditure-state.md` (branch
`claude/macro-factor-app-dev-6twv5o`), which resolved
`docs/ADAPTIVE_ENGINE_GAPS.md`'s "Nullable engine fields vs. NOT NULL schema
columns" gap.

## Resolved: `loadRecords()` and recompute share one date snapshot

`loadRecordsAt(today)` is now the internal source for both the public
`loadRecords()` method and `recomputeExpenditure()`. A recompute threads one
`LocalDate` through history assembly, damping-anchor selection, and
persistence, so a clock rollover during network I/O cannot make a prior-day
row look like the current row or change the assembled date range mid-call.

## Resolution: when a row is persisted

`ExpenditureRepository.recomputeExpenditure` only inserts into
`expenditure_estimates` when `estimate.estimateKcal`, `estimate.windowStart`,
and `estimate.windowEnd` are all non-null. Per `AdaptiveEngine.estimateExpenditure`,
that excludes exactly two cases: a completely empty `records` list, and a
holding result with no `previousEstimateKcal` to carry forward (the very
first-ever recompute with insufficient data). Every other holding or
updating result has all three fields defined and is persisted normally.
This mirrors this repo's existing precedent (`daily_nutrition_totals`,
`weight_trend_points`) that absence of a row is how "nothing to report" is
represented -- never a fabricated sentinel value. A caller finding no
persisted row means "never enough information to say anything, ever";
CLAUDE.md's "missing-data holding is a valid state and must be visible" is
satisfied because ordinary holding-with-a-carried-number IS persisted.

## Resolution: `inputs` jsonb contract

`expenditure_estimates.inputs` (`jsonb not null default '{}'::jsonb`) had no
defined contract anywhere in this repo's source-of-truth docs before this
slice -- `docs/ADAPTIVE_ENGINE_CONTRACT.md` and `adaptive_engine.py` never
mention it. Separately, the table has **no `explanation` text column** at
all, even though `ExpenditureEstimate.explanation` is a real, always-computed
human-readable field. This slice's resolution: `inputs` stores exactly
`{"explanation": "<ExpenditureEstimate.explanation>"}`. This is this
repo's explicit product choice for this slice, not an inherited spec --
record it here rather than let a future reader assume `inputs` has a richer
or different contract than it actually does.

## Resolved: damping anchor design

Before this slice, `AdaptiveEngine.estimateExpenditure` applies at most one
damping step per call. Naively, calling `recomputeExpenditure()` repeatedly
in one day (e.g. once per app open, or again after the user fixes a logging
gap) would chain a fresh damping step onto the previous call's already-damped
output each time — drifting toward the raw value purely from call count,
defeating `docs/ADAPTIVE_ENGINE_CONTRACT.md`'s damping cap. Equally naively,
short-circuiting same-day calls to skip recomputation would freeze a `holding`
result telling the user "fix your logging gap" even after they did, violating
CLAUDE.md's "missing-data holding is a valid state and must be visible" rule.

The implemented fix: every call always recomputes, but damping is anchored to
the **last genuine (non-same-day) persisted estimate**, never to a same-day
row's own already-damped output. When a same-day row already exists, it is
**upserted in place** rather than accumulated, since it represents "today's
estimate", re-evaluated, not a new historical entry. See the doc comment above
`app/src/main/java/com/macroplus/app/data/ExpenditureRepository.kt` for the
full reasoning.

## Resolved: same-day expenditure persistence is atomic and stale-safe

Migration `003_expenditure_daily_upsert.sql` adds a unique index on
`(user_id, window_end)`. `recomputeExpenditure()` now uses one PostgREST
upsert for a concrete result, so repeated refreshes and concurrent callers
cannot create duplicate current-day rows or lose the row between a delete and
an insert. It also threads one `today` value through history assembly,
damping, and persistence, removing the old midnight boundary mismatch.

If a same-day recompute no longer has enough information to produce a
persistable result, it deletes only that day's derived row. This prevents
`getLatestEstimate()` from returning a stale concrete estimate after the
underlying history was removed. Existing databases with duplicate derived rows
must still reconcile those rows before applying migration 003; the migration
does not guess which derived record to keep.

## Still open: damping cap ambiguity in contract doc

`docs/ADAPTIVE_ENGINE_CONTRACT.md` says "limit the weekly update step" when
describing the damping cap, but the constraint this code enforces is
effectively per-calendar-day: at most one 100 kcal/day step per day, which
happens to match the "100 kcal/day" figure mentioned in the same sentence but
not the word "weekly". This ambiguity in the contract doc itself was never
reconciled — is the cap truly weekly, or is the per-day implementation
correct? Per CLAUDE.md's "if implementation and documentation disagree, stop
and reconcile them" rule, this remains an open doc/implementation tension
without a decided resolution. Record it rather than silently choosing one.

## Still open: unbounded bulk reads risk silent truncation

`ExpenditureRepository` depends on `DayStatusRepository.listStatuses()`,
`LogRepository.listDailyTotals()`, and `WeightRepository.listEntries()` to
fetch full user history. These three functions have no row limit and order
results ascending; if a project-level PostgREST max-rows cap is enforced, the
newest data (which this repository most needs for accurate, up-to-date
estimates) would be silently dropped. This is the same pre-existing pattern
already noted in `docs/WEIGHT_LOGGING_GAPS.md` and `docs/TREND_VISUALISATION_GAPS.md`
for the sibling repositories. It is now also expenditure-state's problem, not
a new discovery, just a new place it bites.

## Resolved: Coach-triggered recomputation

**Resolved** by `docs/superpowers/plans/2026-08-03-weight-coach-screens.md`:
`CoachScreen`/`CoachViewModel` is now the first caller. It calls
`ExpenditureRepository.recomputeExpenditure()` on every screen resume
(`ON_RESUME`), though a 60-second throttle (see CoachViewModel.kt) skips the
actual recompute if the last successful refresh was recent, to avoid redundant
writes on rapid tab-switching or rotation. There is intentionally no background
worker yet; recomputation is on-demand when the Coach screen is active.

## Still open: no test exercises `recomputeExpenditure` end-to-end

Like `TrendRepository`, `ExpenditureRepository` has no dedicated unit test --
there is no mock/fake Postgrest client in this codebase, and no live
Supabase project exists in this sandbox. The persist/skip decision and the
full-history-fetch behavior are covered by manual review and by the
already-tested pure functions it composes (`ExpenditureRecordAssembler`,
`AdaptiveEngine.estimateExpenditure`), but the wiring between them is not
independently tested. Worth revisiting if this codebase ever adds a fake
Postgrest client for repository-level tests.

## Fixed during final review: a declared fast was never actually countable

`ExpenditureRecordAssembler.assemble` originally set `calories =
totalsByDay[day]` unconditionally. A real fasted day has no
`food_log_entries` rows at all (the user ate nothing), so
`daily_nutrition_totals` never has a row for it -- `totalsByDay[day]` is
`null`, identical to a genuinely unlogged day. `AdaptiveEngine.nutritionIsCountable`
only treats `"fasted"` as countable when `calories == 0.0` explicitly, so
without a fallback every declared fast silently read as uncountable, no
different from `"unlogged"` -- a user who fasts even one day a week could
never satisfy the 6-of-7-day coverage gate in either seven-day period,
permanently `holding`. Fixed: a day with no totals row AND
`nutritionStatus == DayStatus.FASTED` now gets `calories = 0.0`. This is the
explicit user declaration the schema itself documents ("'fasted' is a user
declaration, not an inference from missing entries"), not an inferred
zero-fill -- an `UNLOGGED` day is untouched and still gets `null`.

## Resolved: daily status is now an explicit user action

`DailyLogScreen` now exposes `complete`, `partial`, and `fasted` controls and
persists them through `DayStatusRepository.setStatus`. It also allows an
explicit `unlogged` reset. The app deliberately does **not** auto-mark a day
complete merely because an entry exists: a user may have logged only part of a
day. Likewise, it does not infer a fast from missing rows. A complete day
requires at least one logged entry in the UI; a fasted day is the explicit
zero-intake declaration that the assembler can count. This closes the prior
path where every day remained `UNLOGGED` and expenditure could never leave
`holding` because the status table was never written.

The current screen also edits explicitly selected historical dates. The
remaining limitation is intentional: it does not offer bulk status editing or
an import path for historical day classifications.

## Partially resolved: the weekly-check-in slice reuses assembled records, but not the persisted-estimate converter

`WeeklyCheckIn.weeklyCheckIn` (already built) calls
`AdaptiveEngine.estimateExpenditure` itself, needing the same full-history
`DailyRecord` series and the same damping anchor this repository already
assembles. `ExpenditureRepository` now exposes `loadRecords():
Pair<List<DailyRecord>, Double?>`, and `CheckInRepository` uses that seam, so
it no longer has to re-implement full-history fetch →
`WeightTrendCalculator.averageByLocalDay` → `ExpenditureRecordAssembler.assemble`
→ damping-anchor selection. The remaining gap is narrower: the
persisted-row-to-domain converter is still local to the repository, and the
damping-anchor rule is documented in KDoc rather than exposed as a separately
tested value object. A future refactor can add
`PersistedExpenditureEstimate.toDomain()` and a shared result type without
changing the current product path.

## Still open: a persisted row does not record which `EngineConfig` produced it

`recomputeExpenditure` constructs `EngineConfig()` inline every call.
CLAUDE.md's adaptive-engine rules require these parameters stay
"configurable, deterministic, versioned, and explainable", but nothing on a
persisted `expenditure_estimates` row records which parameter values
produced it -- `inputs` holds only `explanation`, and `method` is always the
schema's static default string, regardless of engine version. After any
future change to `EngineConfig`'s defaults, historical rows become
un-re-derivable and un-explainable: there's no way to tell, from the row
alone, whether it was computed under the old parameters or the new ones.

## Still open: unbounded row growth with no dedup

A user who remains in a sustained `holding` state can still accumulate one
derived row per calendar day of app usage indefinitely, even when every row is
identical to the last. Migration 003 prevents same-day duplicates, but there is
not yet a retention policy or cross-day deduplication, and
`getLatestEstimate()` (the only reader) never surfaces the growing row count.

## Still open: damping cadence is a function of how often the app is opened, not of elapsed time

The "damping cap ambiguity in contract doc" gap above assumes
`recomputeExpenditure` runs roughly daily. It doesn't have to: a user who
opens the app weekly gets one 100 kcal step per week of elapsed time; one
who opens it after a month away gets exactly one 100 kcal step for that
entire month, with no catch-up toward the raw value. The effective
adaptation rate is therefore a function of app-open frequency, not of
elapsed time or of how much new data arrived. This interacts directly with
the still-open "no scheduling/trigger" gap above -- resolving that one
(e.g. a background recompute on a fixed schedule) would also resolve this
one.

## Fixed during adversarial review: nullable estimate fields were defaulted to null across an upsert

`NewExpenditureEstimate.previousEstimateKcal`, `.rawEstimateKcal`, and
`.trendSlopeKgPerWeek` were declared with `= null` defaults. Once migration
003 turned `recomputeExpenditure`'s write from delete-then-insert into
`upsert(onConflict = "user_id,window_end")`, that became the same bug
`NewCheckIn` was deliberately built to avoid: `encodeDefaults = false` omits a
field left at its default from the JSON body, and PostgREST's upsert only
updates columns present in the body, so a prior non-null value survived the
update untouched.

The path is real, not theoretical. `AdaptiveEngine.estimateExpenditure`'s
holding branch can return `rawEstimateKcal`/`trendSlopeKgPerWeek` as `null`
while still producing a persistable `estimateKcal`/`windowStart`/`windowEnd`,
so a same-day transition from `updating` to `holding` (e.g. the user deletes
log entries) would have left the earlier observed raw estimate and trend
slope on the row indefinitely, misrepresenting what the engine actually
observed -- a provenance violation, not just stale UI. Fixed by making all
three required (non-defaulted) constructor parameters;
`ExpenditureRepository.recomputeExpenditure` already passed all three
explicitly off the engine result, so no call-site logic changed.
`ExpenditureEstimateModelsTest` now pins that they encode as explicit JSON
nulls.

## Still open: migration 003 ships no duplicate-reconciliation query

Migration 003 correctly refuses to guess which duplicate
`(user_id, window_end)` derived row should win, but it also gives an operator
nothing to run first -- applied to a database that already has duplicates it
simply hard-fails. See the combined note for migrations 002 and 003 in
`docs/WEEKLY_CHECKIN_GAPS.md` ("migrations 002 and 003 ship no
duplicate-reconciliation query") for the pre-flight queries an operator needs
and why resolving the results is a judgement call rather than a script.

## Still open: `window_start`/`window_end` do not mean what a future consumer may assume

`window_end` is always the day `recomputeExpenditure` ran on, and
`window_start` is `max(firstEverRecordDay, window_end - 13 days)` -- a
rolling 14-day *analysis* window feeding the trend/mean-intake calculation,
not "the period this estimate covers" in a calendar sense.
`weekly_check_ins.week_start`/`week_end` are a completely different concept
(a specific Mon-Sun check-in period). A future consumer should not join or
compare these two date-range pairs as if they describe the same thing.
