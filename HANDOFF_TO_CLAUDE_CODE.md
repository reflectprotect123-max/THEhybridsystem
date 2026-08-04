# Claude Code handoff

Paste this as the opening instruction after opening the repository:

> Work inside this new MacroTrack repository. Read `CLAUDE.md`, `README.md`,
> `supabase/migrations/001_macro_foundation.sql`, and
> `docs/ADAPTIVE_ENGINE_CONTRACT.md` before editing. Run the Python tests first.
> Then audit the migration and Android starter for compile/runtime issues. Build
> the product in vertical slices: food search/barcode logging, snapshot-based
> daily logging, recipes/custom foods, weight trend and expenditure state,
> then weekly adaptive check-ins. Keep the adaptive logic deterministic and
> explainable. Do not claim MacroFactor algorithm parity, do not invent food
> data, do not put a service-role key in Android, and do not silently treat
> missing nutrition data as zero. After each slice, add tests and report what
> actually passed.

Native Android requirement: keep the client as a standard Kotlin/Jetpack
Compose Android app. Do not replace it with React Native, Expo, Flutter,
WebView, or a JavaScript bridge. The APK must be produced by the included
Gradle Android module (`:app`).
