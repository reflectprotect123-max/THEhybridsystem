# Macro+ release candidate — 2026-08-04

This snapshot is the current source release candidate for the Macro+ MVP.
It includes the Compose app, Supabase migrations, Australian food importers,
deterministic adaptive-engine reference, and offline regression fixtures.

## Included product flow

- Auth-gated Daily Log with historical date navigation and explicit complete,
  partial, fasted, and unlogged states.
- Open Food Facts search, exact barcode camera lookup, custom foods, quick-add,
  favorites, recipes, serving scaling, source snapshots, and safe log deletion.
- Weight logging and trend display.
- Persisted macro goal programs, weekly check-ins, accepted next-week targets,
  and program-safe check-in provenance when a goal changes during a week.
- Migrations 001 through 005, including RLS reference hardening and the
  program-aware weekly-check-in natural key and owner-scoped program policy.

## Verification completed here

- `python3 -m unittest discover -s tests -v` — 9 tests passed.
- `python3 -m py_compile *.py` — passed.
- Static call-site audit completed for the new routes, repository interfaces,
  migrations, date propagation, numeric boundaries, and Coach target repair.
- The final native handoff ZIP will be checked with `unzip -t` before delivery.

## Verification blocked by this environment

The Gradle launcher starts from a local Gradle 9.5.1 distribution, but Java
cannot resolve the Android Gradle Plugin from Google Maven in this workspace.
There is also no Android SDK, emulator, or live Supabase project here.
Therefore this snapshot makes no Android compile, APK, device, or live-database
claim.

On the Android development machine, apply the migrations, create a local
`local.properties` with the publishable Supabase key, then run:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

After the first successful build, walk the acceptance path in
`docs/VERIFICATION_2026-08-04.md` and the manual checklist in `HANDOFF.md`.
