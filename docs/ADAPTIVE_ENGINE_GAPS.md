# Adaptive engine — known gaps

Recorded per this repo's evidence-discipline convention (`CLAUDE.md`: "if
implementation and documentation disagree, stop and reconcile them instead
of silently choosing one" / "record unresolved gaps in `docs/` rather than
filling them with plausible numbers"). From the final whole-branch review of
`docs/superpowers/plans/2026-08-03-adaptive-engine-kotlin-port.md` (branch
`claude/macro-factor-app-dev-6twv5o`).

The Kotlin port (`app/src/main/java/com/macrotrack/app/domain/{AdaptiveEngine,
MacroTargeting,WeeklyCheckIn,AdaptiveEngineModels}.kt`) is a verified-faithful
mirror of `adaptive_engine.py` — the final review diffed ~450,000 randomized
inputs against the live Python reference and found zero behavioral
divergence. These gaps are not bugs introduced by the port; they're places
where `adaptive_engine.py` itself (ground truth for the port) diverges from
either `docs/ADAPTIVE_ENGINE_CONTRACT.md` or the already-migrated Supabase
schema. The port correctly surfaces them rather than papering over them —
they need resolving before a repository/persistence layer sits on top of
this engine, which is why they're recorded now rather than silently.

## `weeklyCheckIn`'s "ready" status has no home in `weekly_check_ins.status`

`supabase/migrations/001_macro_foundation.sql`'s `weekly_check_ins` table has:

```sql
status text not null check (status in ('pending', 'held', 'accepted', 'declined'))
```

`weeklyCheckIn`/`weekly_check_in` returns `status = "held"` on the holding
path (matches the CHECK constraint) but `status = "ready"` on the
success path — `"ready"` isn't one of the four allowed values. A future
repository writing a check-in result straight through would need to map
`"ready"` to `"pending"` (awaiting the user's accept/decline) itself; the
engine's own vocabulary and the schema's vocabulary are not the same
vocabulary, and nothing today reconciles them.

By contrast, `expenditure_estimates.state`/`confidence` CHECK constraints
(`'holding'`/`'updating'` and `'holding'`/`'low'`/`'medium'`/`'high'`) match
`ExpenditureEstimate.state`/`confidence` exactly — this is an isolated gap on
the check-in result, not a systemic mismatch across the whole engine.

## Nullable engine fields vs. `NOT NULL` schema columns

`expenditure_estimates.estimate_kcal`, `window_start`, and `window_end` are
all `not null`. `ExpenditureEstimate.estimateKcal`/`windowStart`/`windowEnd`
are nullable in the Kotlin (matching the Python, which returns `None`/`null`
on real, reachable paths — e.g. an empty records list, or a holding result
with no `previous_estimate_kcal` to carry forward). A repository inserting
an `ExpenditureEstimate` straight into `expenditure_estimates` will hit a
`NOT NULL` violation on those paths unless something decides what to persist
instead — and that's a product decision (skip the insert entirely while
holding? persist a sentinel? widen the column?), not something to invent
ad hoc in repository code the first time it happens to compile.

## `program_update` module is documented but never emitted

`docs/ADAPTIVE_ENGINE_CONTRACT.md`'s check-in module list names four
modules: `partial_logging`, `weigh_in`, `logging_break`, `program_update`
("present the proposed calorie and macro targets for approval"). Neither
`adaptive_engine.py` nor its Kotlin port ever emits `program_update` — the
"ready" path returns `modules = []` and puts the proposed targets directly
on the result instead. The doc and the executable reference already
disagreed before this port; the port just makes that visible in Kotlin too
rather than resolving it. Either the contract doc needs updating to drop
`program_update` (if targets-on-the-result is the intended design), or the
engine needs a fourth module emitted on the ready path — whichever the
product decision is, it isn't encoded anywhere yet.
