# Expenditure state — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From building
`docs/superpowers/plans/2026-08-03-expenditure-state.md` (branch
`claude/macro-factor-app-dev-6twv5o`), which resolved
`docs/ADAPTIVE_ENGINE_GAPS.md`'s "Nullable engine fields vs. NOT NULL schema
columns" gap.

## Still open: `loadRecords()` extraction (from the weekly-check-in slice) introduced a narrow midnight race

`docs/superpowers/plans/2026-08-03-weekly-check-in.md` extracted
`recomputeExpenditure()`'s fetch/assemble/anchor logic into a new
`loadRecords()` method so `CheckInRepository` could reuse it. That refactor
was reviewed and confirmed behavior-preserving, with one narrow exception:
`recomputeExpenditure()` and `loadRecords()` now each call
`LocalDate.now(zoneId)` independently, separated by a `getLatestEstimate()`
network round trip, where before there was exactly one `today` shared by
both. If the clock rolls over in that window, `recomputeExpenditure()`'s
own `isPreviousFromToday` (computed against the earlier `today`) can end up
true while `loadRecords()`'s internal anchor selection (computed against
the later `today`) treats the same row as *not* from today — the net
effect is that a genuine prior-day estimate row gets deleted as if it were
today's own row, losing one day of persisted estimate history. The damping
anchor itself stays correct either way (verified during review), so this
is a data-retention gap, not a correctness regression in the numbers.
Fixable by threading one shared `today` into `loadRecords()` (e.g. as an
optional parameter defaulting to a fresh `LocalDate.now(zoneId)`, so
`CheckInRepository`'s call site doesn't need to change) — not done here to
keep the refactor itself minimal and reviewable.

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

**Resolved** by `docs/superpowers/plans/2026-08-03-weight-coach-screens.md`:
`CoachScreen`/`CoachViewModel` is now the first caller. It calls
`ExpenditureRepository.recomputeExpenditure()` on every screen resume
(`ON_RESUME`), though a 60-second throttle (see CoachViewModel.kt) skips the
actual recompute if the last successful refresh was recent, to avoid redundant
writes on rapid tab-switching or rotation. Left below for historical context.

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

## Still open: nothing writes `daily_log_status`, so this slice cannot produce a non-holding estimate yet

The only writer of `daily_log_status` is `DayStatusRepository.setStatus`,
and nothing in `app/src/main` calls it -- there is no ViewModel/UI layer yet
(`MainActivity.kt` is the only non-repository, non-domain file in the app
module). `AdaptiveEngine.nutritionIsCountable` requires `status ==
"complete"`. With no status rows ever written, every assembled day is
`DayStatus.UNLOGGED`, `nutritionDays` stays `0`, the coverage gate never
passes, and `recomputeExpenditure()` stays in `holding` with a null anchor
forever -- meaning **no row is ever actually inserted** with the code as it
stands today, even for a user who logs food faithfully every day. Whether
logging food should auto-mark a day `complete`, or whether that should stay
an explicit end-of-day user action, is an undecided product question. This
is the single biggest thing standing between this slice and a working
expenditure state, and it belongs to whichever future slice adds day-status
UI or an auto-completion rule.

## Still open: the weekly-check-in slice cannot reuse this pipeline without duplicating its subtlest logic

`WeeklyCheckIn.weeklyCheckIn` (already built) calls
`AdaptiveEngine.estimateExpenditure` itself, needing the same full-history
`DailyRecord` series and the same damping anchor this repository already
assembles. But `ExpenditureRepository`'s interface exposes neither: only the
final `ExpenditureEstimate`. A future check-in repository would have to
re-implement full-history fetch → `WeightTrendCalculator.averageByLocalDay`
→ `ExpenditureRecordAssembler.assemble` → damping-anchor selection from
scratch -- and the anchor rule (same-day → `previous.previousEstimateKcal`,
otherwise → `previous.estimateKcal`) exists only as prose in this file's
`recomputeExpenditure` KDoc, not as a reusable, tested unit. Getting it
wrong risks reintroducing the exact per-invocation drift bug this slice
already spent three review rounds fixing, with a second, independent
`estimateExpenditure` call that could silently disagree with the persisted
row. Recommended before that slice starts: expose the assembled records and
resolved anchor from `ExpenditureRepository` (e.g. a
`suspend fun loadRecords(): Pair<List<DailyRecord>, Double?>`), and add a
`PersistedExpenditureEstimate.toDomain(): ExpenditureEstimate` converter --
today, every consumer that wants `explanation` back has to dig
`inputs["explanation"]` out of a `JsonObject` by hand, exactly as
`ExpenditureEstimateModelsTest` does.

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

A user who stays in a sustained `holding` state (see the "nothing writes
`daily_log_status`" gap above -- today, every user) would, once something
does call `recomputeExpenditure()` regularly, persist one row per calendar
day of app usage indefinitely, even when every one of those rows is
identical to the last. There is no dedup for consecutive unchanged rows and
no retention policy, and `getLatestEstimate()` (the only reader) never
surfaces the growing row count to anything. Related to, but distinct from,
the concurrent-duplicate-rows gap above.

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

## Still open: `window_start`/`window_end` do not mean what a future consumer may assume

`window_end` is always the day `recomputeExpenditure` ran on, and
`window_start` is `max(firstEverRecordDay, window_end - 13 days)` -- a
rolling 14-day *analysis* window feeding the trend/mean-intake calculation,
not "the period this estimate covers" in a calendar sense.
`weekly_check_ins.week_start`/`week_end` are a completely different concept
(a specific Mon-Sun check-in period). A future consumer should not join or
compare these two date-range pairs as if they describe the same thing.
