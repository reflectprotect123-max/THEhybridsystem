# Expenditure state — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From building
`docs/superpowers/plans/2026-08-03-expenditure-state.md` (branch
`claude/macro-factor-app-dev-6twv5o`), which resolved
`docs/ADAPTIVE_ENGINE_GAPS.md`'s "Nullable engine fields vs. NOT NULL schema
columns" gap.

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
**replaced in place** (delete then insert) rather than accumulated, since it
represents "today's estimate", re-evaluated, not a new historical entry. See
the doc comment directly above `recomputeExpenditure()` in
`app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt` for the
full reasoning.

## Still open: same-day delete-then-insert is not atomic

The replace logic (delete a same-day row, then insert a fresh one) is two
separate Postgrest HTTP requests with no database transaction wrapping them.
If the process dies between the delete and the insert, today's row is lost.
However, this is self-healing: the very next call to `recomputeExpenditure()`
recomputes from the surviving prior-day anchor and lands on the identical
value, so at most one derived (fully reconstructable) row is lost, never
user-entered data.

A more serious case: two concurrent calls to `recomputeExpenditure()` (e.g.
two coroutines, or two app instances) can each independently pass the
same-day check, both insert, and leave two rows sharing the same `window_end`
date. The computed *value* stays correct in this case (both rows hold the same
number), but the duplicate row does not self-clean and wastes storage.

Currently there are **zero callers** of `recomputeExpenditure()` or
`getLatestEstimate()` anywhere in `app/src` (verified by search), so this is
not reachable today. When the first caller is wired up (likely a ViewModel),
flag this explicitly. Recommended fix: add a `unique (user_id, window_end)`
constraint on `expenditure_estimates` via a migration, and switch the
delete-then-insert to a single `upsert` operation, mirroring how
`weight_trend_points` already uses upsert-by-primary-key in `TrendRepository`.

## Still open: same-day persist transition leaves a stale row

If `recomputeExpenditure()` previously persisted a result but a later call
within the same day transitions to a non-persistable result (all of
`estimateKcal`, `windowStart`, `windowEnd` become null), the old persisted
row is left untouched. For example, if a user's entire logged history is
deleted mid-session, `recomputeExpenditure()` correctly computes and returns
a `holding` result with everything null, but skips the whole persist-or-replace
block (since the guard condition `if (estimateKcal != null && ...)` fails) —
leaving today's previously-persisted row in the table. A subsequent call to
`getLatestEstimate()` then returns the stale `updating` row, disagreeing with
what `recomputeExpenditure()` just returned.

This is a rare edge case (entire logged history deletion mid-session is
unusual), not fixed in this plan. Record it for future reference.

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

## Still open: no scheduling/trigger for `recomputeExpenditure`

Like `TrendRepository.recomputeTrend`, `ExpenditureRepository.recomputeExpenditure`
is entirely caller-driven -- nothing in this slice calls it. Whoever wires up
the first caller needs to decide when recomputation happens: on app open, on
a schedule, after every log/weigh-in, or on-demand when an expenditure
screen opens. Not attempted here -- this slice is data-layer only.

## Still open: no test exercises `recomputeExpenditure` end-to-end

Like `TrendRepository`, `ExpenditureRepository` has no dedicated unit test --
there is no mock/fake Postgrest client in this codebase, and no live
Supabase project exists in this sandbox. The persist/skip decision and the
full-history-fetch behavior are covered by manual review and by the
already-tested pure functions it composes (`ExpenditureRecordAssembler`,
`AdaptiveEngine.estimateExpenditure`), but the wiring between them is not
independently tested. Worth revisiting if this codebase ever adds a fake
Postgrest client for repository-level tests.
