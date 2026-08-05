# Nutrition-label OCR scanner — known gaps

## Implemented boundary

The scanner is a client-side acquisition layer only, entered by an explicit
"Scan nutrition label" button on Create Custom Food (never auto-triggered):

1. CameraX (`ImageCapture`) takes one full-resolution still photo.
2. The JPEG bytes are decoded to a `Bitmap` in-process and handed to bundled
   ML Kit Text Recognition; the original camera buffer is closed immediately
   after, and the bitmap itself is never persisted or uploaded - only the
   recognized text (`OcrLine` bounding boxes) leaves the capture function.
3. `NutritionLabelParser` groups the recognized lines into rows by vertical
   position (not text order), then reads each row left-to-right, matching
   `energy`/`protein`/`fat`/`carbohydrate` label rows and excluding their
   `saturated`/`sugars` sub-rows.
4. Every field the parser isn't confident about is left `null`; the caller
   (`CreateCustomFoodViewModel.onNutritionLabelScanned`) only fills currently
   blank form fields and never overwrites a value the user already typed
   (CLAUDE.md rule #1).
5. If nothing recognizable is found, the screen shows an explicit retry
   prompt and stays open rather than silently falling back to a default.

Only macros + serving size are scanned - no micronutrients, no confidence
score/UI (a blank field means uncertain), and no serving size is ever
defaulted to 100 g: the label's own printed serving-size text is parsed, or
the field stays blank.

## Verification still required

This workspace has no Android SDK, Gradle installation, emulator, or physical
device, so the following have not been verified here:

- Gradle resolution of `com.google.mlkit:text-recognition:16.0.1` (Google's
  Maven is network-blocked in this sandbox);
- compilation against the repository's Kotlin/Compose/AGP/CameraX versions;
- that `ImageProxy.planes[0].buffer` reliably contains a decodable JPEG for
  every device/OEM camera stack an `ImageCapture.OnImageCapturedCallback`
  still can produce;
- OCR accuracy against real photographed Australian nutrition panels under
  ordinary shop/kitchen lighting, at realistic photo resolutions and angles;
- the dynamic row-grouping tolerance (median line height x 0.5, floor 12px)
  against real photo line-height variance - it replaced an earlier fixed
  12px constant that was only ever validated against synthetic test
  fixtures, not real OCR output;
- camera permission behavior on Android 13+/14+ (shared code path with the
  barcode scanner, not re-verified separately here).

## Fixes applied 2026-08-05 (final whole-branch review)

An independent whole-branch review (after all six implementation tasks and
their own per-task reviews had already passed) found two Critical defects
that no per-task review could see, because both are runtime/device-only
failure modes invisible to a diff read of a single task and to the
GitHub Actions build (which cannot run Gradle in this sandbox either, so
these were never actually exercised until this review's careful manual
trace of the CameraX/ML Kit contract):

1. **The per-capture executor was shut down before CameraX could ever use
   it.** A Task 3 fix round (addressing an earlier "leaked executor" finding)
   added `executor.shutdown()` immediately after
   `imageCapture.takePicture(executor, callback)`. `takePicture` does not
   submit anything to that executor synchronously - it stores the executor
   and dispatches to it later, asynchronously, once the capture pipeline has
   an image ready. Calling `shutdown()` right after `takePicture()` returns
   put the executor into a state that rejects that later dispatch
   (`RejectedExecutionException`), so `onCaptureSuccess`/`onError` would
   never run on a real device: the Capture button would stay disabled
   forever with a stuck spinner, on every single capture attempt. Fixed by
   removing the per-capture executor entirely and passing the already-
   available main executor to `takePicture` instead - `takePicture`'s
   callback body here only decodes bytes and immediately hands off to ML
   Kit's own internal thread pool, so it doesn't need a dedicated background
   thread, and this also removes the original per-capture-thread-leak
   concern the Task 3 fix round was trying to solve, without needing any
   executor lifecycle management at all.
2. **`InputImage.fromMediaImage()` was fed a JPEG-format image, which it does
   not accept.** `ImageCapture.OnImageCapturedCallback.onCaptureSuccess`
   delivers its in-memory still as a JPEG-format `android.media.Image`,
   while `InputImage.fromMediaImage()` only accepts
   `NV21`/`YV12`/`YUV_420_888`. On a real device this would have thrown
   `IllegalArgumentException` on every capture, before any text recognition
   ever ran. `BarcodeScannerScreen.kt`'s existing, already-shipped
   `InputImage.fromMediaImage` usage is correct because it reads YUV frames
   from `ImageAnalysis`, not JPEG stills from `ImageCapture` - this screen is
   the first `ImageCapture` consumer in the app, and the pattern was copied
   across a format boundary the barcode scanner never crosses. Fixed by
   decoding the JPEG bytes (`ImageProxy.planes[0].buffer`) into a `Bitmap`
   via `BitmapFactory.decodeByteArray` and calling
   `InputImage.fromBitmap(bitmap, rotationDegrees)` instead. This has the
   side benefit of letting the camera's buffer close immediately after
   decoding, strengthening the "photo discarded immediately, never persisted"
   property.
3. **No exception handling around the capture callback body** meant either
   defect above (or any other exception) would leak the `ImageProxy` (no
   `image.close()`) and leave the screen stuck with no retry prompt, since
   neither `onLines` nor `onError` would fire. Fixed by wrapping the callback
   body in `try`/`catch`/`finally`, so every exit path - success, decode
   failure, or an unexpected exception - closes the image and, on any
   failure, calls `onError()` to surface the existing retry prompt.
4. **Row-grouping tolerance was a fixed 12px constant**, which only makes
   sense at one photo resolution. This screen captures a full-resolution
   still (often thousands of pixels tall) rather than a small preview frame,
   so a fixed pixel gap could plausibly split a label from its own value row
   on real photos even though it passed every synthetic test fixture. Fixed
   by scaling the tolerance to the label's own median recognized-line height
   (floor 12px, to avoid degenerate behavior on very few lines), keeping row
   grouping resolution-independent. Re-verified against all 9 parser unit
   tests (7 original + 2 added by this review) with a real `kotlinc` + JUnit
   run in this sandbox - all pass.

None of the four fixes above could be exercised against a real device,
emulator, or compiled Gradle build in this sandbox - the same Android
SDK/Gradle/Google Maven access gaps recorded above still apply. The parser-
only fix (#4) and its test coverage were independently compiled and run for
real (`kotlinc` 2.1.0 + JUnit 4.13.2, fetched from GitHub Releases/Maven
Central, both reachable here); the CameraX/ML Kit fixes (#1-#3) were verified
only by careful manual reading against the documented CameraX
`ImageCapture.takePicture`/`ExecutorService.shutdown()` contract and the ML
Kit `InputImage` accepted-format list, not by a real build or device.

## Known limitations carried forward (not fixed, tracked here instead)

These fail safe - a limitation here means a blank field and a retry prompt,
never a wrong or invented number - so they were deliberately left as
follow-up rather than blocking this merge:

- **Per-serving column selection is purely positional** (the leftmost value
  cell in a row), with no header-row detection. Correct for the standard
  FSANZ two-column ("per serving" then "per 100g") panel this app targets,
  but a panel printing only a per-100g column would have those values read
  as if they were per-serving.
- **A row where OCR merges a label and its value into one single line**
  (e.g. one recognized line reading `"Protein 3.2g"` instead of two separate
  lines) is dropped entirely rather than parsed - the row-based reader
  expects the label and value as separate cells.
- **Units printed in the row label rather than the value cell** (e.g.
  `"Energy (kJ)" | "1234"`) aren't handled - the value cell has no `kJ`/`cal`
  token for `parseEnergyKcal` to match, so energy is left blank on that panel
  style.
- **US-style label wording** (`"Total Fat"`, `"Calories"` as the energy row
  label) doesn't match this parser's Australian-panel keyword set and yields
  a retry prompt - correct for this app's AU-only scope, not a defect.
- Scanned values prefill as a raw `Double.toString()` (e.g. `"200.0"`, and a
  kJ->kcal conversion can render as a long decimal like
  `"199.80879541108987"`). Not a data-correctness issue - no value is
  invented and the user reviews/edits before saving - but a rough first
  impression worth trimming in a future UI-polish pass.

Official references consulted (accessible via Maven Central/GitHub, not
Google's docs site, which is network-blocked here):

- [CameraX `ImageCapture` reference (via Maven Central artifact javadoc)](https://developer.android.com/reference/androidx/camera/core/ImageCapture)
- [ML Kit Text Recognition v2 for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
