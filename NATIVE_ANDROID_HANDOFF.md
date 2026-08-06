# Macro+ — native Android handoff

## What this is

Macro+ is a standard native Android application:

- Kotlin source under `app/src/main/java`;
- Jetpack Compose UI and Android Navigation;
- CameraX + bundled ML Kit for barcode acquisition;
- Supabase Kotlin Auth/PostgREST/Realtime client;
- Supabase/Postgres migrations under `supabase/migrations`;
- deterministic adaptive logic implemented in both Python and Kotlin.

There is no React Native, Expo, Flutter, WebView, JavaScript bridge, or web
client inside the Android module. Claude must keep that boundary intact.

## First run on Claude's Android machine

1. Open the repository root in Android Studio with JDK 17 and the Android 17
   (API 37) SDK installed.
2. Create `local.properties` beside `settings.gradle.kts`:

   ```properties
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_PUBLISHABLE_KEY=your-anon-or-publishable-key
   ```

   Never put a Supabase service-role key in this file or in the APK.
3. Apply the migrations in filename order:

   ```bash
   supabase db push
   ```

4. Run the build and unit tests:

   ```bash
   ./scripts/verify_native_source.sh
   ./gradlew :app:compileDebugKotlin
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```

5. Install the generated debug APK on a physical Android device. A camera
   barcode test should use a real Australian packaged-food EAN-13/EAN-8 code
   that exists in the seeded `foods` table.

## Required manual acceptance path

Sign up → sign in → move Daily Log to a previous date → create and log a
custom food → quick-add an entry → favorite a food → create and log a recipe →
scan a known barcode → scan an unknown barcode and confirm the manual-search
fallback → log weight → inspect the trend → open Coach → save a goal → run a
check-in → accept a ready proposal → verify `macro_program_days` rows for the
next week → change the goal and confirm old check-in provenance remains
separate.

Rotate the device while testing each core screen. Confirm that a process
recreation does not duplicate entries or re-run a pending check-in decision.

## Verification boundary from this workspace

Passed here:

- Python regression suite: 9/9;
- Python syntax compilation;
- native-source audit and repository call-site audit;
- published dependency-coordinate audit;
- Gradle 9.5.1 launcher startup.

Not possible here:

- Android compilation and APK generation, because the sandbox has no Android
  SDK and Java dependency resolution cannot reach Google Maven;
- Supabase migration execution against a live project;
- emulator or physical-device camera testing.

Do not report the APK as built until the commands above succeed on the Android
machine.
