# Verification record — 2026-08-04

## Passed in this workspace

- `python3 -m unittest discover -s tests -v` — 9 tests passed.
- `python3 -m py_compile *.py` — passed.
- Source call-site audit — all `CheckInRepository`, `NewCheckIn`,
  `CoachViewModel`, and `FoodSearchScreen` implementations/callers were
  updated for the persisted-goal and custom-food slices.
- Static call-site audit — historical-date navigation, quick-add, favorites,
  recipe builder, `MacroProgramDay` persistence, and migrations 004/005
  references
  are wired across their current callers and test fakes.
- Persistence hardening audit — the Add Log Entry route declares its date
  argument, numeric boundaries reject non-finite values, selected-date
  status/delete operations cannot overwrite a newly selected day, and
  same-week check-ins are keyed by macro program through migration 005, whose
  policy also keeps that program reference owner-scoped.
- Coach consistency audit — check-in resolution commits the user decision
  before writing derived next-week targets; an accepted decision retries a
  missing target on a later refresh, and Coach gates check-in on an existing
  weigh-in.
- Python reference/Kotlin contract update — the ready check-in now emits the
  documented `program_update` module; Python tests pass with the new assertion.
- Native Android handoff ZIP integrity — verified with `unzip -t` before
  delivery.
- Native source audit — no React Native, Expo, Flutter, WebView, or JavaScript
  bridge references exist in the Android module.
- Repeatable native-source check — `scripts/verify_native_source.sh` runs the
  forbidden-runtime/import/placeholder checks followed by the Python suite and
  syntax compilation.
- Published-coordinate audit — AGP 9.3.0, Kotlin 2.3.21, Compose BOM
  2026.06.00, AndroidX lifecycle-runtime-compose 2.10.0, Navigation Compose
  2.9.0, CameraX 1.6.1, bundled ML Kit 17.3.0, Supabase BOM 3.7.0, Ktor 3.5.1,
  and kotlinx.serialization JSON 1.9.0 were cross-checked against known
  published version numbers from prior knowledge. They were NOT verified to
  resolve in this sandbox's Maven access — see below, Google Maven is
  unreachable here, so no dependency in this list was actually confirmed to
  resolve from its configured repository in this workspace.
- Local Gradle runner — Gradle 9.5.1 starts successfully from a downloaded
  local distribution; dependency resolution cannot continue because Java in
  this sandbox cannot reach Google Maven.

## Could not run here

`./gradlew :app:compileDebugKotlin --no-daemon` was also attempted with a local
Gradle 9.5.1 distribution. It stopped before compilation because Java could
not resolve the Android Gradle Plugin from Google Maven, and this environment
has no Android SDK or emulator. Therefore this workspace makes no Android
compilation claim. Run these on an Android machine with the required SDKs and
dependency access:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Manual acceptance path still required

After the first successful build and Supabase migration, walk:

1. Sign up/sign in.
2. Move Daily Log to a previous date, then open Food Search → Create custom
   food → create and log it; verify the selected historical date receives it.
3. Exercise Quick Add, favorite a result, and create a recipe with one food
   and one custom-food ingredient; search and log the recipe.
4. Change the Coach goal rate, save it, leave the screen, and confirm it
   reloads after returning.
5. Log a weigh-in, run a check-in, and confirm the persisted row has the
   active `program_id`.
6. Accept a ready check-in and confirm `macro_program_days` contains the
   next week's target rows with `source = accepted_check_in`.
7. Change the goal again and confirm the old check-in does not appear as the
   current program's proposal.
8. Scan a known and unknown barcode, then complete the existing daily-log and
   weight flows.
