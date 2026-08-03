# Session handoff — MacroTrack

Written 2026-08-03 so the next session can pick up exactly where this one
left off. Read this first, then `CLAUDE.md`.

## Repo state

- Branch `claude/macro-factor-app-dev-6twv5o` is pushed to
  `reflectprotect123-max/thehybridsystem` with one commit: the imported
  MacroTrack scaffold (Supabase migration, `adaptive_engine.py`, Open Food
  Facts + AUSNUT/NUTTAB importers, Android starter module, tests).
- Verified in this session: `python3 -m py_compile *.py` clean, all 9
  `python3 -m unittest discover -s tests -v` tests pass, the
  `adaptive_engine.py --input examples/checkin.json` CLI example runs.
- Fixed a real bug: `gradle/wrapper/gradle-wrapper.properties` pointed at
  `gradle-9.5-bin.zip`, which doesn't exist (only `9.5.0`/`9.5.1` were ever
  released). Fixed to `9.5.1` and generated the missing `gradlew`,
  `gradlew.bat`, and `gradle-wrapper.jar`.
- NOT verified: the Android module itself. This sandbox has no Android SDK
  and its network policy blocks `dl.google.com` (Google's Maven repo), so
  Gradle can't even resolve the Android Gradle Plugin here. Kotlin 2.3.21,
  supabase-kt bom 3.7.0, and ktor 3.5.1 were confirmed as real published
  versions via Maven Central. AGP `9.3.0`, Compose BOM `2026.06.00`, and
  `compileSdk`/`targetSdk = 37` were NOT verified — check these in Android
  Studio's SDK Manager before the first Gradle sync.

## Plugins installed this session

Installed via the `claude` CLI (present at `/opt/node22/bin/claude`) into
the shared user config — **they were not active in the session that
installed them and need a restart to load**. This restart is why this file
exists.

- `superpowers@superpowers-marketplace` v6.2.0 —
  github.com/obra/superpowers-marketplace
- `ui-ux-pro-max@ui-ux-pro-max-skill` v2.11.0 —
  github.com/nextlevelbuilder/ui-ux-pro-max-skill (styles/palettes/
  typography/chart/stack-guideline database, includes Jetpack Compose)
- `caveman@caveman` — github.com/juliusbrussee/caveman (ultra-compressed
  communication mode; installs `SessionStart`/`UserPromptSubmit` hooks and
  an MCP server, so it actively changes response style once active)

**First thing to do on reopen: confirm `superpowers` shows up as an
available skill.** If it doesn't, the plugin install didn't take effect and
needs re-checking with `claude plugin list`.

## What's next

The user asked to start the **food repository slice** — the next step in
`CLAUDE.md`'s recommended implementation order (step 2, after the schema
validation done in this session):

> Build the food repository: exact barcode lookup, name/brand search,
> serving scaling, favorites, recent history, custom foods, and recipes.

Explicit instruction from the user: **use the `superpowers` skill to
develop the plan** for this slice before implementing it. Do not skip this
and plan solo — that's the whole reason this handoff file exists instead of
just continuing.

Reference docs, in priority order: `CLAUDE.md`, `README.md`,
`supabase/migrations/001_macro_foundation.sql`,
`docs/ADAPTIVE_ENGINE_CONTRACT.md`. When the slice reaches Compose UI
screens, use `ui-ux-pro-max` for style/palette/typography choices.
