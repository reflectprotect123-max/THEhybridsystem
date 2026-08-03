# Adaptive engine contract

This document is the handoff contract between the Python reference and the
Kotlin production implementation.

## Inputs

Each calendar day can contain:

* complete logged calories;
* a weight observation, optionally missing;
* an explicit nutrition state: `complete`, `partial`, `fasted`, or `unlogged`.

The engine must not turn `partial` or `unlogged` into zero calories. A declared
fast is countable only when the app stores zero calories explicitly.

## State machine

```text
insufficient history/coverage -> HOLDING
adequate intake + weight data -> UPDATING
UPDATING + accepted check-in -> next macro-program week
UPDATING + declined check-in -> preserve current program
```

Holding is a normal state, not an error. The UI should explain the missing
data and carry forward the last high-confidence expenditure value.

## Calculation contract

1. Smooth observed weight with an EWMA (`alpha = 0.20` by default).
2. Estimate the trend slope in kg/week using the smoothed series.
3. Estimate expenditure as:

   `mean logged kcal/day - trend slope kg/day × 7,700 kcal/kg`.

4. Clamp the raw estimate to a configurable sanity range.
5. When a previous estimate exists, limit the weekly update step to the
   configured damping limit (100 kcal/day by default).
6. Set the next calorie target to:

   `estimated expenditure + signed target rate kg/week × 7,700 / 7`.

7. Allocate protein and fat from explicit user preferences, then allocate the
   remaining calories to carbohydrate. These defaults are product defaults,
   not medical advice or a claim that MacroFactor uses the same values.

## Coverage contract

The reference requires two consecutive seven-day periods with at least six
countable nutrition days and at least one weigh-in per period. The six-of-seven
nutrition and one-per-week weight thresholds are based on MacroFactor's public
help documentation. The choice to require two periods, the EWMA alpha, the
7,700 kcal/kg conversion, and the 100-kcal damping cap are explicit product
parameters and are not presented as validated reconstructions of MacroFactor.

## Check-in modules

The first deterministic module set is:

* `partial_logging` — ask the user to classify incomplete days;
* `weigh_in` — ask for the missing weigh-in;
* `logging_break` — explain why the last reliable estimate is being carried
  forward;
* `program_update` — present the proposed calorie and macro targets for
  approval.

The app should keep the modules explainable and allow the user to skip or
decline a recommendation. Recommendations are informational, not punishment or
retroactive calorie debt.

## Known limits

The public MacroFactor articles describe the conceptual use of intake and
weight-trend data, but do not publish the exact smoothing kernel, custom BMR
equations, activity factors, confidence math, or all edge-case rules. This
repository therefore cannot honestly claim algorithmic parity. The constants
must remain configurable, versioned, and test-covered as product decisions.

