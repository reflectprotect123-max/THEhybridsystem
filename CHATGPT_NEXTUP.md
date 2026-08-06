# Macro+ — Quick handoff for ChatGPT (2026-08-05)

## TL;DR — what happened

Claude completed a 6-task subagent-driven development cycle to add nutrition-label OCR to the Create Custom Food screen. The feature is now:
- **Fully merged to main** (commit 3e11009)
- **Compiled and tested** via real GitHub Actions CI (first non-trivial code to actually build on real hardware)
- **Ready for device testing** but not yet field-tested

The food seed accumulation got to 471/5000 Open Food Facts products before the OFF API went 401. This is an external blocker, not a code bug. **Do not run the retailer scrapers** (Coles/Woolworths) — the user explicitly declined, citing ToS/legal exposure.

## Current state: what compiles, what doesn't, what you should test first

✅ **Compiles and passes CI**: nutrition-label OCR, barcode scanner, core UI, adaptive engine, backend repositories

❌ **Never run on real hardware**: full end-to-end flow (sign up → scan label → log entry → weigh in → check in)

🤔 **Unknown at device-level**: any of the camera/OCR flows under real-world conditions (varying lighting, label orientations, phone resolutions, poor OCR results)

## What to do if you're starting fresh

1. **Read `CLAUDE.md`** first — it has the non-negotiable data rules (never invent nutrition numbers, never show a blank day as zero calories, etc.).
2. **Read `HANDOFF.md`** for detailed architecture + verified-vs-unverified boundaries.
3. **Read the feature docs**:
   - `docs/NUTRITION_LABEL_OCR_GAPS.md` — what was learned during the 5-round fix cycle
   - `docs/BARCODE_SCANNER_GAPS.md` — camera/ML Kit context from the barcode feature
4. **Build the app locally**:
   ```bash
   ./gradlew :app:compileDebugKotlin
   ./gradlew :app:assembleDebug
   ```
5. **Run on a device or emulator** and walk the feature:
   - Food Search → Create Custom Food → Tap "Scan Nutrition Label"
   - Photograph a real nutrition panel (any AU/US label, any brand)
   - Verify fields pre-fill with recognized values, leave blank if OCR fails
   - Edit any values, save the custom food, log it, confirm persistence

## The food data problem (and why it matters)

You have 471/5000 products from Open Food Facts (10 API pages successfully harvested, then 401 on page 11). The import scripts are ready:
- `import_openfoodfacts.py` — for OFF API or static exports
- `import_ausnut.py` — for FSANZ Excel/CSV files from https://www.foodstandards.gov.au

To complete the seed:
1. **Check if OFF API recovers** — if you see 401s again, ping their support or download a static dump from https://world.openfoodfacts.org/data/
2. **Run AUSNUT import** (most reliable):
   ```bash
   python3 import_ausnut.py \
     --nutrients "AUSNUT 2023 - Food nutrient profiles.xlsx" \
     --foods "AUSNUT 2023 - Food details.xlsx" \
     --measures "AUSNUT 2023 - Food measures.xlsx" \
     --output seed_foods_ausnut.sql
   ```
   Apply the generated SQL to Supabase (via dashboard SQL editor, service-role key only — **never** use anon key).

**Do NOT run the retailer catalogue builders** (`scripts/build_retailer_catalogue.py` or the Coles/Woolworths-specific variants). The user rejected this approach on legal/ToS grounds. Those scripts are in the repo for reference only.

## Why this handoff happened

The user asked for TEXT ONLY output and explicitly requested a handoff to ChatGPT. All code is on `main` and pushed to GitHub. The nutrition-label OCR feature was the last slice to complete; everything before it (backend, core UI, barcode scanner, adaptive engine) was done in earlier Claude sessions and reviewed/fixed in a round-trip ChatGPT session, then hand-reviewed back.

## Key files to know

| File | Purpose |
|------|---------|
| `CLAUDE.md` | Non-negotiable product/data rules — read first |
| `HANDOFF.md` | Full architecture + boundary details |
| `README.md` | Setup, import commands, feature overview |
| `adaptive_engine.py` | Python reference for macro-targeting logic (ported to Kotlin) |
| `app/src/main/java/com/macroplus/app/ui/search/NutritionLabelScannerScreen.kt` | Camera + OCR capture |
| `app/src/main/java/com/macroplus/app/domain/NutritionLabelParser.kt` | OCR result → domain model (with sub-row exclusion guards) |
| `docs/NUTRITION_LABEL_OCR_GAPS.md` | What OCR *doesn't* do (units, merged lines, etc.) — read before shipping new features |
| `docs/ADAPTIVE_ENGINE_CONTRACT.md` | What adaptive logic boundary covers vs. doesn't (for macro targeting decisions) |
| `supabase/migrations/` | All 5 schema migrations — run in order |

## The next big decision points

1. **Device testing of the OCR feature** — the whole camera/ML Kit/navigation flow is untested on real hardware. Expect edge cases (permutations of label layouts, phone resolutions, lighting, OCR confidence).

2. **Food database seeding** — OFF API is blocked; AUSNUT is ready to run. Pick your path (wait for OFF recovery vs. proceed with AUSNUT only vs. investigate licensed alternatives).

3. **Feature scope for the next slice** — step 7 in CLAUDE.md has three unstarted slices: URL recipe import, speech/voice logging, image AI helpers. Pick one or design something new.

4. **Production prep** — schema is migration-ready, adaptive engine is Kotlin-ported, UI is built. A real Supabase instance and Play Store package setup are the remaining infra tasks.

## Firm boundaries (do not violate)

- **Never invent food data** — if OCR/search fails, leave fields blank or let the user fill in.
- **Never show a blank day as zero calories** — historical logs must be explicit (complete, partial, fasted).
- **Do not run retailer scrapers** — the user said no, and it's a legal/ToS boundary.
- **Keep the service-role Supabase key out of the Android app** — only the anon/publishable key + RLS.
- **Do not commit large generated SQL files** — they're disposable outputs (CLAUDE.md rule #7).

---

If there's ambiguity about *why* something was built a certain way, check the docs (especially `docs/superpowers/plans/*.md` for the detailed rationales). If a doc and the code disagree, **stop and reconcile** — don't silently pick one (CLAUDE.md rule #9).

Good luck!
