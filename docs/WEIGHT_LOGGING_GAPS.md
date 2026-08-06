# Weight logging — known gaps

Recorded per this repo's evidence-discipline convention: unresolved gaps go
here, not filled with guesses. From the final whole-branch review of
`docs/superpowers/plans/2026-08-03-weight-logging.md` (branch
`claude/macro-factor-app-dev-6twv5o`).

## `measured_at`'s round trip is asymmetric

`WeightRepository.logWeight` writes `Instant.toString()` — the `Z`-suffixed
ISO-8601 form (`2026-08-03T06:30:00Z`). Reads come back through PostgREST,
which renders a Postgres `timestamptz` via `to_json` — that produces the
**offset** form instead (`2026-08-03T06:30:00+00:00`, with fractional
seconds when present). `WeightEntry.measuredAt` is an opaque `String`, so
nothing in this slice breaks — decode doesn't parse it. It bites the first
consumer that does: `Instant.parse` (`DateTimeFormatter.ISO_INSTANT`)
requires the `Z` designator and rejects `+00:00`. The safe call for a future
reader is `OffsetDateTime.parse(s).toInstant()`, not `Instant.parse(s)`.

`WeightModelsTest`'s fixtures all use the `Z` form only, so they exercise
the write side, not the read side. Verification needs a real Supabase
project (not available in this sandbox): insert one row via `logWeight`,
read it back, and record the exact `measured_at` string PostgREST returns.

Precedent: `RecentLogModels.createdAt: String` already keeps a `timestamptz`
column as an unparsed String — the model choice here is consistent with
that. The gap is that no layer yet owns actually parsing it.

## Nothing bridges `listEntries()`'s sparse instants to `AdaptiveEngine`'s dense local-day series

`AdaptiveEngine` (`app/src/main/java/com/macroplus/app/domain/AdaptiveEngine.kt`,
`DailyRecord`) consumes at most one `weightKg: Double?` per `LocalDate`, in a
dense series where gap days are explicit nulls — `weightTrend` carries the
last trend across those nulls, and `periodCoverage` counts `weightKg != null`
days for the one-weigh-in-per-seven-days gate. `WeightRepository.listEntries`
returns sparse rows keyed by an `Instant`. Building the trend-visualisation
slice on top of this needs three decisions none of which belong in an ad-hoc
ViewModel:

- **Which timezone maps `measured_at` to a day.** The nutrition side is
  already keyed on device-local dates (`food_log_entries.log_date`,
  `daily_log_status.log_date` are `date` columns fed from
  `LocalDate.toString()`). Bucketing weight by UTC instead would misalign
  the two — at AEST (UTC+10) an 08:00 weigh-in falls on the *previous* UTC
  day. Given this product's Australian focus, that's not theoretical; it
  moves `weightDays` in `estimateExpenditure`'s coverage gate and shifts the
  trend slope's x-axis.
- **How to combine multiple same-day weigh-ins.** `weight_entries` has no
  uniqueness constraint on `(user_id, day)`, and `logWeight` neither upserts
  nor dedupes, so two saves in one day legitimately produce two rows. Mean,
  first, and last are all defensible conventions. Per CLAUDE.md's
  adaptive-engine rules this is a product parameter — it belongs in
  versioned config alongside `EngineConfig` with fixture tests, not invented
  ad hoc at the call site.
- **Filling gap days.** The repository returns only rows that exist; the
  engine needs an explicit `null` for days that don't.

Related: `deleteEntry` is a correct hard delete (the schema has no
`deleted_at` on `weight_entries`), but `weight_trend_points` (out of scope
for this slice) stores *derived* trend rows, and nothing invalidates them
when a source weigh-in is deleted. Whoever builds the trend slice owns that.

## `listEntries` has no row limit, and ascending order makes truncation land on the newest data

`WeightRepository.listEntries` issues `order("measured_at", Order.ASCENDING)`
with no `limit`. Supabase projects can set an API "max rows" cap (dashboard
default 1000); when set, PostgREST truncates the response with no error and
no indication. Because the order is ascending, truncation drops the
*newest* rows — exactly the ones the expenditure window and trend slope
depend on — so the failure mode is a silently stale trend, not a visible
error. Daily weigh-ins cross 1000 rows in roughly 2.7 years, so this isn't
imminent, but it is silent when it arrives. Needs one of: pagination via
`range`, an explicit `limit`, or descending order with a `limit` and a
client-side reverse (which at least fails toward recent data first).

## No `source` vocabulary and no update path

`daily_log_status.status` got a `DayStatus` constants object plus
`require(status in VALID_STATUSES)` in `DayStatusRepository` because the DB
has a CHECK constraint. `weight_entries.source` has **no** CHECK constraint,
and `WeightRepository.logWeight` accepts arbitrary free text with no
constants object. When a Health Connect / smart-scale import adapter lands,
nothing stops `"healthconnect"` vs `"health_connect"` vs `"Health Connect"`
from drifting into the same column — and CLAUDE.md rule #3 (preserve source
provenance) is only as reliable as that vocabulary being consistent.

Separately, there is no `updateEntry`. Correcting a mistyped weight today
means delete-then-re-log, which changes both `id` and `created_at`. Both of
these look like deliberate scope calls for this slice; they're just not
written down anywhere else, so recording them here.
