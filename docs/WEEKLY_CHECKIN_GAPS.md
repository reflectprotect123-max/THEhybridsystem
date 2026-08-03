# Weekly check-in — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From building
`docs/superpowers/plans/2026-08-03-weekly-check-in.md` (branch
`claude/macro-factor-app-dev-6twv5o`), which resolved
`docs/ADAPTIVE_ENGINE_GAPS.md`'s "weeklyCheckIn's 'ready' status has no home
in weekly_check_ins.status" gap.

## Resolution: status vocabulary mapping

`CheckInResult.status` ("held"|"ready") is mapped explicitly in
`CheckInRepository.recomputeCheckIn`: `"ready"` becomes `"pending"`
(awaiting the user's accept/decline via `resolve`), `"held"` is passed
through unchanged (it already matches the schema's CHECK constraint).

## Still open: no real source for `targetRateKgPerWeek`

`CheckInRepository.recomputeCheckIn` requires the caller to supply
`targetRateKgPerWeek` directly -- there is no `macro_programs` read path in
this codebase yet (that table, and the whole coached/collaborative/manual
program concept, is out of scope for every slice on this branch so far).
`program_id` is always `null` on every persisted row for the same reason.
Whoever builds a `macro_programs` slice or the UI layer that lets a user set
a goal rate owns wiring a real source in; nothing here invents a default
rate to paper over the gap.

## Still open: `recomputeCheckIn` requires at least one weigh-in

If `WeightRepository.listEntries(Instant.EPOCH)` returns no rows at all,
`recomputeCheckIn` throws `IllegalStateException` rather than inventing a
placeholder body weight (macro targets are computed directly from it, so a
fabricated number would silently misrepresent the user's real macros). A
future UI must guard against offering a check-in before the user has logged
at least one weigh-in.

## Still open: no scheduling/trigger for `recomputeCheckIn`

Like `TrendRepository.recomputeTrend`/`ExpenditureRepository.recomputeExpenditure`,
`recomputeCheckIn` is entirely caller-driven -- nothing in this slice calls
it, nothing decides which `weekStart`/`weekEnd` to pass. Whoever wires up
the first caller needs to decide the week boundary convention (calendar
week? rolling 7 days since the user's last check-in?) and when
recomputation happens.

## Still open: no test exercises `recomputeCheckIn`/`resolve` end-to-end

Like `TrendRepository`/`ExpenditureRepository`, `CheckInRepository` has no
dedicated unit test -- there is no mock/fake Postgrest client in this
codebase, and no live Supabase project exists in this sandbox. The
status-mapping and precondition logic are covered by manual review and by
the already-tested pure functions it composes (`weeklyCheckIn`,
`AdaptiveEngine.estimateExpenditure`), but the wiring between them is not
independently tested.

## Fixed during implementation review (context for future maintainers)

### NewCheckIn fields: no defaulting to null

`NewCheckIn`'s numeric fields (`previousExpenditureKcal`, `observedExpenditureKcal`,
`proposedExpenditureKcal`, `proposedCalories`, `proposedProteinG`, `proposedCarbsG`,
`proposedFatG`) and `resolvedAt` are required (non-defaulted) constructor parameters.
This was necessary because `CheckInRepository.recomputeCheckIn` writes via `upsert`,
and kotlinx.serialization's `encodeDefaults = false` omits fields left at their default
`null` value from the JSON body entirely. PostgREST's upsert only updates columns
present in the body — a value can legitimately go from a real number back to `null`
across recomputes (e.g., a `"ready"` week's `proposed_calories` must clear to `null`
if the next recompute is `"held"`), but omitting the field would leave a stale prior
value in the database forever. Similarly, `resolvedAt` must explicitly become `null`
when a previously-accepted/declined week is recomputed — it cannot be omitted and
left stamped next to a fresh `pending`/`held` status.

### Observed expenditure kcal: rawEstimateKcal, not estimateKcal

`CheckInRepository.recomputeCheckIn` stores `result.estimate.rawEstimateKcal` as
`observedExpenditureKcal` (not `result.estimate.estimateKcal`). On a holding result,
`estimateKcal` is the carried-forward anchor from `AdaptiveEngine.estimateExpenditure`,
not anything actually observed that week. `rawEstimateKcal` is `null` on exactly the
same paths where there is nothing real to report, which is what an "observed" value
should mean.

### Resolve: compare-and-set to prevent silent overwrites

`CheckInRepository.resolve` includes `eq("status", "pending")` in its update filter,
making it a compare-and-set operation. Without this guard, two concurrent resolve
calls (or a stale UI) could both pass the require check and the second would silently
overwrite the first's outcome. With this guard, a losing resolve matches zero rows
and `decodeSingle()` throws loudly instead of silently losing work.

### Modules: encoded through CheckInModuleDto, not hand-built JSON

`CheckInRepository.recomputeCheckIn` encodes modules through `CheckInModuleDto` via
`Json.encodeToJsonElement(...)` rather than hand-building `{"key":...,"action":...}`
JSON objects. This ensures the decode side (`PersistedCheckIn.modules: List<CheckInModuleDto>`)
and write side can never silently disagree on field names.

### Upsert conflict target: explicit onConflict on the real natural key

`recomputeCheckIn`'s upsert did not set `onConflict`, so postgrest-kt defaulted to
targeting `weekly_check_ins`'s primary key (`id uuid primary key default
gen_random_uuid()`). Since `NewCheckIn` never includes `id` (server-generated), that
default conflict target never actually fires -- a second `recomputeCheckIn` call for
the same week would instead hit the table's *separate* `unique(user_id, week_start)`
constraint directly, which throws a raw duplicate-key database error rather than
updating the existing row. `DayStatusRepository`/`TrendRepository`'s tables don't have
this problem because their composite natural key (`(user_id, log_date)`/`(user_id,
trend_date)`) *is* their primary key, so the same unset-`onConflict` pattern happens
to target the right columns there. `weekly_check_ins`'s surrogate `id` breaks that
assumption. Fixed by setting `onConflict = "user_id,week_start"` explicitly in the
upsert call.

## Still open: damped expenditure estimate not persisted

`proposed_expenditure_kcal` and `proposed_calories` both hold the same calorie target
value (by the adaptive engine's own design — the target already factors in the user's
signed goal rate). `observed_expenditure_kcal` now holds the raw, undamped observed
value (from `result.estimate.rawEstimateKcal`). However, the actual *damped*
expenditure estimate (`result.estimate.estimateKcal` — the number the calorie target
was derived from, after the 100kcal/day damping cap was applied in
`AdaptiveEngine.estimateExpenditure`) is not stored anywhere on the check-in row.

A future reader trying to understand "why is my target X" from the persisted row alone
cannot reconstruct it — they would need to solve
`observed - damping_offset = target - signed_rate_adjustment`, which is not derivable
from what is persisted. This is a real gap in explainability (CLAUDE.md: "calm
explainable coaching experience"), and represents a genuine product decision (add a
column? overload an existing one? accept that a future UI must always read this from
live state, not from history?) rather than something to be silently invented now.

## Still open: recomputing an already-accepted/declined check-in silently reverts it

Since `resolvedAt` is always passed as `null` on recompute (necessary to correctly
clear a *previous* resolution when transitioning states), recomputing a week the user
already explicitly accepted reverts its status back to `pending`/`held` and erases all
trace that they ever accepted it — there is no history table. This is defensible
(matches "recomputation reflects current reality"), but it is a real behavior a future
caller must be explicitly aware of. A future UI designer should decide on purpose
whether recompute should ever touch an already-resolved week, rather than discovering
this behavior by surprise.

## Still open: losing concurrent resolve() call throws opaque error

A losing concurrent `resolve()` call fails loudly (correct) by matching zero rows in
the compare-and-set filter, but the actual exception it throws is a generic
`NoSuchElementException: List is empty` from `decodeSingle()`, not a message that
explains "someone already resolved this week." A future caller should either catch
this exception and translate it to a clear message to the user/logs, or
`CheckInRepository.resolve` could be changed to use `decodeSingleOrNull()` and throw
an explicit, clearer `error(...)` with a descriptive message instead.
