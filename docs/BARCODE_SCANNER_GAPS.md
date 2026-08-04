# Barcode scanner — known gaps

## Implemented boundary

The camera scanner is a client-side acquisition layer only:

1. CameraX supplies `ImageProxy` frames.
2. Bundled ML Kit decodes supported retail barcode formats.
3. The raw, trimmed ML Kit value is passed to `FoodSearchViewModel`.
4. `FoodRepository.findByBarcode()` performs the exact database lookup.
5. A match proceeds to the existing Add Log Entry screen; a miss returns to
   Food Search with an explicit error.

The scanner does not pad UPC values, convert UPC-A to EAN-13, query an
external food service, or create a food record. Those would be data decisions
and are deliberately outside this slice.

## Verification still required

This workspace has no Android SDK, Gradle installation, emulator, or physical
device, so the following have not been verified here:

- Gradle resolution of CameraX `1.6.1` and bundled ML Kit
  `com.google.mlkit:barcode-scanning:17.3.0`;
- compilation against the repository's Kotlin/Compose/AGP versions;
- camera permission behavior on Android 13+ and Android 14+;
- preview orientation/cropping on portrait devices;
- decoding success for Australian EAN-13/EAN-8 packages under ordinary shop
  lighting;
- navigation state restoration after rotation or process recreation;
- exact barcode coverage in the seeded Supabase foods table.

The dependency/API choices were checked against the official Android CameraX
release notes and Google ML Kit barcode-scanning documentation, but only a
real Android build and device walk can close the verification gap.

Official references:

- [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)
- [ML Kit barcode scanning for Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- [ML Kit BarcodeScanner API](https://developers.google.com/android/reference/com/google/mlkit/vision/barcode/BarcodeScanner)

## Fixes applied 2026-08-04 (adversarial-review cluster C)

Three findings from an independent adversarial review of this screen were
addressed in `BarcodeScannerScreen.kt`:

1. **Deprecated `ImageAnalysis.setTargetResolution`.** The `ImageAnalysis`
   builder now uses `ResolutionSelector` with a `ResolutionStrategy` targeting
   1280x720 and `FALLBACK_RULE_CLOSEST_HIGHER`, matching the CameraX 1.3+
   `ResolutionSelector` API rather than the resolution API deprecated since
   CameraX 1.3. This still cannot be compiled here (no Android SDK/Gradle
   access), so the class/method names were checked only against known
   CameraX `ResolutionSelector`/`ResolutionStrategy` API shape, not verified
   by a real build.
2. **No recovery path for a permanently-denied camera permission.** The
   screen now tracks whether a permission request has been attempted at
   least once and checks
   `ActivityCompat.shouldShowRequestPermissionRationale()`. Once the
   permission is denied and the system will no longer show a rationale (the
   "permanently denied" state), the permission screen switches to a message
   plus an "Open app settings" button that launches
   `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the app's package,
   instead of re-issuing a `permissionLauncher.launch()` call that the system
   would silently ignore.
3. **Double system-bar inset padding.** Removed the redundant
   `.systemBarsPadding()` modifier from `ScannerOverlay` and
   `PermissionRequestContent`. Those composables render inside a route whose
   content already sits inside the outer navigation Scaffold's
   `Modifier.padding(paddingValues)`, which carries the full system-bar
   insets since the barcode scanner route hides the bottom bar. The
   full-bleed camera preview `Box`/`AndroidPreview` in the main composable
   never had `systemBarsPadding()` and is unchanged, so the live preview
   still renders edge-to-edge behind the system bars, consistent with normal
   camera-app UX.

None of the three fixes could be exercised against a real device, emulator,
or compiled build in this sandbox — the same Android SDK/Gradle/Google Maven
access gaps recorded above still apply. They were verified only by careful
manual reading of the diff against known CameraX/AndroidX/Activity Result API
surfaces.
