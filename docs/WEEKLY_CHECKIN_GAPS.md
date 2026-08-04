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

## Resolved: persisted source for `targetRateKgPerWeek`

The Coach screen now persists the selected rate in `macro_programs` through
`MacroProgramRepository`. It derives the database `goal` from the signed rate,
keeps one active program per user through migration
`002_active_macro_program.sql`, and passes that program's ID into
`weekly_check_ins.program_id`. A changed rate pauses the old program and starts
a new one, preserving the meaning of historical check-ins. A fresh app session
therefore reloads the saved goal instead of silently reverting to 0.0.

The user must still choose and save the rate before a check-in can run. That is
intentional: the app does not invent a goal rate for a new user.

## Resolved: same-week goal changes preserve check-in provenance

Migration `005_checkin_program_provenance.sql` replaces the original
`(user_id, week_start)` uniqueness key with
`(user_id, program_id, week_start)`. The repository uses that same natural key
for recompute upserts and passes `program_id` through resolve. A goal change
during the current week can therefore create a new program's proposal without
rewriting the prior program's check-in.

## Resolved in the Coach UI: a weigh-in is required before check-in

If `WeightRepository.listEntries(Instant.EPOCH)` returns no rows at all,
`recomputeCheckIn` throws `IllegalStateException` rather than inventing a
placeholder body weight (macro targets are computed directly from it, so a
fabricated number would silently misrepresent the user's real macros). A
Coach refresh now checks for an existing weigh-in and shows a direct "Log
weight" path before offering the check-in button. The repository precondition
remains as a defensive boundary for non-UI callers.

## Still open: no automatic scheduling/trigger for `recomputeCheckIn`

The Coach screen is now the first caller. The current product choice is an
explicit user tap on the "Check in" button, not an automatic or scheduled
job; the week label is the most-recent-Monday through the following Sunday,
using the device-local `CoachViewModel.currentWeekStart`. This resolves the
first-caller gap, but does not make the computation automatic and does not
resolve the separate week-bounding issue below.


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

`recomputeCheckIn`'s upsert did not set `onConflict`. `UpsertRequestBuilder.onConflict`
defaults to `null`, so postgrest-kt never sent an `on_conflict` query param at all --
it's PostgREST itself that then falls back to the table's primary key
(`weekly_check_ins.id uuid primary key default gen_random_uuid()`) as the implicit
`ON CONFLICT` target for `resolution=merge-duplicates`. Since `NewCheckIn` never
includes `id` (server-generated), that fallback target never actually conflicts with
anything -- a second `recomputeCheckIn` call for the same program and week instead hit the table's
*separate* `unique(user_id, program_id, week_start)` constraint directly, which throws a raw
duplicate-key database error rather than updating the existing row. `DayStatusRepository`/
`TrendRepository`'s tables don't have this problem because their composite natural key
(`(user_id, log_date)`/`(user_id, trend_date)`) *is* their primary key, so the same
unset-`onConflict` pattern happens to fall back onto the right columns there.
`weekly_check_ins`'s surrogate `id` breaks that assumption. Fixed by setting
`onConflict = "user_id,program_id,week_start"` explicitly in the upsert call
and applying migration 005.

## Still open: the damped expenditure estimate is reconstructible but not versioned

`proposed_expenditure_kcal`/`proposed_calories` hold the calorie target;
`observed_expenditure_kcal` holds the raw, undamped observed value
(`result.estimate.rawEstimateKcal`). The *damped* expenditure estimate itself
(`result.estimate.estimateKcal`, after `AdaptiveEngine.estimateExpenditure`'s
100kcal/day damping cap) is not stored as its own column -- but it IS
reconstructible from what's on the row:
`estimateKcal = previousExpenditureKcal + clamp(observedExpenditureKcal -
previousExpenditureKcal, ±maximumExpenditureStepKcal)`. This was verified
numerically during final review, not just asserted.

The real gap is narrower than "not derivable": reconstruction requires
knowing which `EngineConfig` (specifically `maximumExpenditureStepKcal`) was
in force when the row was written, and no persisted row records that. If
`EngineConfig`'s defaults ever change, historical rows become
un-re-derivable even though the *formula* for reconstructing them hasn't
changed -- the same class of gap `docs/TREND_VISUALISATION_GAPS.md` already
records for `weight_trend_points`' un-versioned `method`. Not fixed here;
recording it is enough per CLAUDE.md's evidence-discipline convention.

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

**Resolved** by `docs/superpowers/plans/2026-08-03-weight-coach-screens.md`
(a final-review fix, not the original plan): `CoachViewModel.resolve` now
catches `NoSuchElementException`, `IllegalArgumentException`, and
`IllegalStateException` separately, in that order, ahead of a generic
`catch (e: Exception)`, and surfaces a distinct user-facing message for
each of the three cases described below, re-fetching the check-in row
afterward so the UI reflects reality. `CheckInRepository.resolve` itself is
unchanged -- the translation happens at the caller, the second option this
section names. Left below for historical context.

A losing concurrent `resolve()` call fails loudly (correct) by matching zero rows in
the compare-and-set filter, but the actual exception it throws is a generic
`NoSuchElementException: List is empty` from `decodeSingle()`, not a message that
explains "someone already resolved this week." A future caller should either catch
this exception and translate it to a clear message to the user/logs, or
`CheckInRepository.resolve` could be changed to use `decodeSingleOrNull()` and throw
an explicit, clearer `error(...)` with a descriptive message instead.

`resolve` in fact has three distinct failure modes a caller needs to handle, not just
this one: `IllegalStateException` (via `error(...)`) when no row exists for that
`weekStart`; `IllegalArgumentException` (via `require(...)`) when the row exists but
isn't `"pending"`; and the `NoSuchElementException` above for a losing compare-and-set.
A single catch-all `catch (e: Exception)` would mask the difference between "nothing
to resolve," "already resolved differently," and "someone else resolved it just now" --
worth three distinct handlers when a UI is built on top of this.

## Still open: `weekStart`/`weekEnd` are row labels only -- `recomputeCheckIn` does not actually compute over that week

`recomputeCheckIn` takes `weekStart`/`weekEnd` as parameters, but nothing about the
computation itself uses them: `loadRecords()` always assembles history from the
earliest known date through *today* (never through `weekEnd`), and
`AdaptiveEngine.estimateExpenditure` internally windows the last 14 days ending at
`ordered.last().day` -- also always today. `weeklyCheckIn` itself never receives
`weekStart`/`weekEnd` at all; the repository only uses them as the row's key. This was
verified concretely during final review: calling `recomputeCheckIn` with a `weekStart`/
`weekEnd` from six months ago still returns and persists *today's* numbers under that
old week's label.

Nothing enforces `weekEnd >= weekStart`, either -- a caller can pass an inverted range
and the row persists with no error from either the repository or the schema.

The realistic failure mode is a future "catch up on missed check-ins" loop that calls
`recomputeCheckIn` once per skipped week: every one of those rows would get identical
numbers under different week labels, silently misstating each row's own provenance
(CLAUDE.md rule #3 -- preserve provenance, don't fabricate it). Before any caller does
that, either `recomputeCheckIn` needs a real accounting for which week it's computing
(e.g. bounding the analysis window by `weekEnd`, not always by today), or the parameter
names need to change to something that doesn't imply the computation is scoped to that
week at all. Not fixed here -- there are no callers yet, so nothing is broken today,
but the very first caller that assumes otherwise will get silently wrong data.

## Still open: a recomputed row has no timestamp for when its current numbers were produced

`created_at` is set once, by the database, on the row's first insert -- an `upsert`
that later replaces the row's contents (via the `onConflict` fix above) does not bump
it. Combined with `resolved_at` always resetting to `null` on recompute, a row that's
been recomputed several times carries no timestamp at all indicating when its *current*
numbers were actually produced. A future "last updated" UI affordance, or an audit
trail, needs either a `updated_at` column or a separate history table -- neither exists
today.

## Fixed during adversarial review: migration 005's unique index ignored NULL `program_id`

`weekly_check_ins_user_program_week_uidx` was created as a plain
`create unique index ... (user_id, program_id, week_start)`. `program_id` is
nullable, and Postgres treats NULLs as distinct in a unique index by default,
so rows with a null `program_id` got no uniqueness protection at all and
`ON CONFLICT (user_id, program_id, week_start)` could never match one -- a
recompute upsert for a program-less check-in would append a new row every
time instead of updating the existing one. Migration 005 now declares the
index `nulls not distinct` (Postgres 15+, supported by Supabase). The
migration file was amended in place rather than superseded by a new one
because nothing has been applied to a live database yet; if that ever stops
being true, this needs a follow-up migration instead of an edit.

## Still open: `saveActive()` is a non-atomic two-step write that can leave a user with no goal

`SupabaseMacroProgramRepository.saveActive` changes a user's goal in two
separate HTTP round trips: first an `update` that sets the existing active
program's `status` to `'paused'`, then an `insert` of the replacement
program. There is no transaction around the pair. If the second call fails
(network drop, RLS rejection, a violated constraint, the process being
killed between the two), the user ends up with **zero** active programs --
their previously saved goal is not merely unchanged, it is gone, because the
old row was already paused. `getActive()` then returns `null`, and the app
behaves as though the user never set a goal.

The failure is also invisible in the UI: the caller surfaces a generic
"couldn't save" message, which reads as "nothing happened" when in fact the
prior state was destroyed. Migration `002_active_macro_program.sql`'s partial
unique index (one active program per user) is what forces the pause-then-
insert ordering in the first place, so this cannot be fixed by reordering
the two calls.

A real fix needs both steps inside one Postgres transaction -- a
`SECURITY INVOKER` RPC (so RLS still applies) that pauses the current active
program and inserts the replacement, returning the new row, called as a
single `rpc(...)` from the repository. Not attempted in this pass: it adds a
new migration and a new server-side function, and the correct error
semantics for the "no active program existed" case need deciding first.

Until then, a caller that sees `saveActive` fail should re-read `getActive()`
before telling the user anything, and must be prepared for it to return
`null`.

## Still open: migrations 002 and 003 ship no duplicate-reconciliation query

Both `002_active_macro_program.sql` and `003_expenditure_daily_upsert.sql`
create a unique index over data that may already contain duplicates, and
both deliberately refuse to guess a winner -- 002's comment says duplicate
active rows "must be reconciled before applying this migration" and 003's
says the same for duplicate `(user_id, window_end)` rows. That refusal is
correct (silently deleting a user's goals would violate CLAUDE.md rule #3),
but the consequence is that applying either migration to a database with
existing data **hard-fails** with a raw `could not create unique index`
error, and neither file gives the operator anything to run first.

Whoever applies these to a database with real data needs to check for and
manually resolve duplicates beforehand, e.g.:

```sql
-- Migration 002: users with more than one active macro program.
select user_id, count(*)
  from public.macro_programs
 where status = 'active'
 group by user_id
having count(*) > 1;

-- Migration 003: duplicate derived estimates for one user and window end.
select user_id, window_end, count(*)
  from public.expenditure_estimates
 group by user_id, window_end
having count(*) > 1;
```

Resolving them is a judgement call, not a mechanical one, which is why it is
not scripted here: for 002, exactly one program per user should stay
`'active'` and the rest should become `'paused'` (they are user-entered
goals -- do not delete them). For 003 the rows are derived state and the
newest `created_at` per group is the defensible keeper, but that is still an
explicit operator decision. This gap stays open until either a documented
runbook or an idempotent pre-flight migration exists.

## Still open: `previous_expenditure_kcal` is a damping anchor, not "last week's expenditure"

`previousExpenditureKcal` is exactly `ExpenditureRepository.loadRecords()`'s second
tuple element: on a day where `recomputeExpenditure()` has already run, the last
*genuine* (non-same-day) persisted estimate's own value; on a user's very first ever
check-in, `null`. A UI rendering this column as "your previous week's expenditure"
would be describing it wrong on both counts -- it's not week-scoped (see the
`weekStart`/`weekEnd` gap above) and it's genuinely absent on a first check-in, which
a naive UI might render as `0` instead of "not yet available."

## Fixed during adversarial review: the Coach refresh throttle could strand a first-time weigh-in

`CoachViewModel.refresh()` gained a 60-second throttle (`lastRefreshedAt`) to
stop `ON_RESUME` from re-triggering the writing `recomputeExpenditure()` on
every rotation or tab re-entry. Once `hasWeighIn` also started being derived
inside `refresh()` (from `WeightRepository.listEntries`), that throttle
reintroduced the weigh-in dead end this file records above, by a new route: a
user with no weigh-ins loads Coach (a *successful* refresh that stamps
`lastRefreshedAt`), taps "Log weight", logs their first ever weigh-in, comes
back, and the resume-triggered `refresh()` is skipped by the throttle — so
`hasWeighIn` stays `false` and the check-in section stays hidden for up to a
minute, with no on-screen control that could recompute it. The pre-existing
`lastRefreshedAt = null` in `checkIn()`'s `IllegalStateException` handler did
not cover this, because that path only runs if the user managed to press
"Check in", which this state never offers.

Fixed by treating `hasWeighIn == false` as "nothing usable loaded yet" for
throttle purposes, alongside the existing `isLoading` first-load bypass, so a
refresh in that state always proceeds. `refresh` also took the explicit
`force: Boolean = false` escape hatch already used by
`WeightViewModel.refresh`, for post-write callers; no Coach caller passes
`force = true` today, and `CoachScreen`'s `ON_RESUME` observer deliberately
still calls the throttled `refresh()`.

The cost is deliberate: a user who has genuinely never weighed in pays a full
refresh (including the `recomputeExpenditure` write) on every resume until
they log one. That is bounded by the user logging a weigh-in, which is exactly
what that screen state is asking them to do.

## Fixed during adversarial review: an error message on Coach was unrecoverable for up to a minute

`CoachScreen` renders `errorMessage` as an **exclusive** branch of its
top-level `when`: while it is non-null the screen is a single line of red text
with no slider, no buttons, and no retry affordance. The only thing that
clears it is a subsequent successful `refresh()` — which the 60-second
throttle could skip. `saveGoal()`'s catch and `refresh()`'s own
`targetSyncError` path both set `errorMessage` without clearing
`lastRefreshedAt`, so a failure there froze the whole screen until the timer
expired. `markResolvedTargetSyncFailure` already cleared the throttle
correctly; the other sites had simply missed it.

The rule is now uniform in `CoachViewModel`: **every** state update that sets a
non-null `errorMessage` also sets `lastRefreshedAt = null`, so the next
resume-triggered refresh genuinely retries. This includes `refresh()`'s
`targetSyncError` and outer catch, `saveGoal()`'s catch, `checkIn()`'s
precondition returns and generic catch, and `resolve()`'s precondition
returns, post-resolve sync-failure message, and per-exception handlers.

### Still open: the exclusive error branch is the underlying UX problem

Clearing the throttle makes the screen recoverable, but the recovery still
depends on the user backgrounding and re-opening Coach. A message like "your
check-in is accepted, but next week's target is still syncing" is informational
and should not be hiding the goal slider, the saved-goal state, and the
check-in status underneath it. The durable fix is to render `errorMessage` as a
banner *above* the normal content rather than instead of it, plus an explicit
"Retry" button that calls `refresh(force = true)` — which is what the `force`
parameter added above exists for. Not done in this pass: it is a screen-layout
change, and which messages are fatal versus advisory needs deciding on purpose
rather than inferred from the current call sites.

## Fixed during adversarial review: goal-rate slider stored float noise

`CoachScreen`'s goal-rate `Slider` is a `Float` widget (`-1f..1f`,
`steps = 19`, i.e. a 0.1 grid) whose value was widened straight to `Double`, so
selecting the "-0.9" tick persisted `-0.8999999761581421` into
`macro_programs.target_rate_kg_per_week`. The label rounds to one decimal, so
the stored goal silently disagreed with the number the user saw, and
`hasUnsavedGoal` could latch on a difference that was pure float noise.
`CoachViewModel.onTargetRateChanged` now snaps the incoming rate to the
slider's own 0.1 grid (`Math.round(rate * 10) / 10.0`) before coercing it into
range. Existing rows written before this fix keep their un-snapped values; no
back-fill is attempted, since nothing here can distinguish float noise from a
rate deliberately set through some other future path.

## Fixed during adversarial review: the post-resolve sync-failure message assumed "accepted"

`markAcceptedTargetSyncFailure` hardcoded "Check-in accepted, but next week's
target could not be synced yet", but it is invoked from every `resolve()`
exception handler — including on the `accepted = false` (decline) path, where
no next-week target is written at all. Telling a user who just declined a
proposal that it was accepted misstates a decision the database has already
committed. It now takes the `accepted` flag (available at all four call sites
as `resolve`'s own parameter) and is named `markResolvedTargetSyncFailure`; the
decline path gets a message that does not claim a target was applied.

The decline path reaching that handler is defensive rather than observed:
after a successful `resolve(accepted = false)` the remaining work is a local
state read that cannot realistically throw. The message is still worth getting
right, because the handler catches a generic `Exception` and nothing guarantees
that stays true.
