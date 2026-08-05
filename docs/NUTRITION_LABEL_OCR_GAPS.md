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
device, so the following have not been verified in this sandbox - however,
after this branch was pushed, GitHub Actions' `Android build` workflow ran
against it for real
([run 30963505520](https://github.com/reflectprotect123-max/THEhybridsystem/actions/runs/30963505520),
commit `6914bb6`) with a genuine Android SDK/Gradle toolchain, and its
`Compile Kotlin`, `Assemble debug APK`, and `Run unit tests` steps all
succeeded - so the first two items below are now closed, and are kept here
only as a record of what this sandbox itself could not confirm:

- ~~Gradle resolution of `com.google.mlkit:text-recognition:16.0.1`~~ -
  confirmed by CI: resolves and compiles cleanly.
- ~~compilation against the repository's Kotlin/Compose/AGP/CameraX
  versions~~ - confirmed by CI: `Compile Kotlin` and `Assemble debug APK`
  both succeeded, including the executor/`InputImage.fromBitmap`/downsample
  changes from both fix rounds and `CreateCustomFoodViewModelTest`
  (`androidx.lifecycle`-dependent, uncompileable in this sandbox) - the
  whole app's `Run unit tests` step passed, including this feature's 12
  parser tests and 2 `CreateCustomFoodViewModel` tests.
- that `ImageProxy.planes[0].buffer` reliably contains a decodable JPEG for
  every device/OEM camera stack an `ImageCapture.OnImageCapturedCallback`
  still can produce;
- OCR accuracy against real photographed Australian nutrition panels under
  ordinary shop/kitchen lighting, at realistic photo resolutions and angles;
- the dynamic row-grouping tolerance (minimum line height x 0.5, floor 12px)
  against real photo line-height variance - it replaced an earlier fixed
  12px constant that was only ever validated against synthetic test
  fixtures, not real OCR output;
- the `MAX_DECODED_DIMENSION_PX = 2048` downsample target against real
  photos - chosen as a reasonable bound for OCR legibility without ever
  being measured against an actual ML Kit recognition-quality/resolution
  tradeoff on a device;
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
   in this round (see below) by scaling the tolerance to the label's own
   line height, keeping row grouping resolution-independent.

None of the four fixes above could be exercised against a real device,
emulator, or compiled Gradle build in this sandbox - the same Android
SDK/Gradle/Google Maven access gaps recorded above still apply. The parser-
only fix (#4) and its test coverage were independently compiled and run for
real (`kotlinc` 2.1.0 + JUnit 4.13.2, fetched from GitHub Releases/Maven
Central, both reachable here); the CameraX/ML Kit fixes (#1-#3) were verified
only by careful manual reading against the documented CameraX
`ImageCapture.takePicture`/`ExecutorService.shutdown()` contract and the ML
Kit `InputImage` accepted-format list, not by a real build or device.

## Fixes applied 2026-08-05, round 2 (independent re-review of round 1)

A second independent review, specifically re-verifying round 1's fixes above
before merge, confirmed all four were genuinely fixed but found that fixing
them had introduced two new device-only defects of the same kind - both
also invisible to CI, and both caught only by careful reading rather than by
a build:

1. **Round 1's Critical-1 fix moved a full-resolution JPEG decode onto the
   main thread.** Passing the main executor to `takePicture` (to solve the
   premature-`shutdown()` bug) meant `onCaptureSuccess`'s JPEG decode - a
   12-50 MP still, hundreds of milliseconds to seconds of work - now ran on
   the UI thread: guaranteed jank, plausible ANR, plausible `OutOfMemoryError`
   on common hardware. Fixed by restoring a *persistent* (not per-capture)
   background executor: `captureExecutor` is created once via `remember` in
   `NutritionLabelScannerScreen` (alongside `textRecognizer`) and shut down
   in the same `onDispose` that unbinds the camera and closes the
   recognizer. This resolves the original Task 3 "leaked executor" finding
   properly (one long-lived executor, disposed once) rather than by
   eliminating background work altogether. `takePicture` is called with
   `captureExecutor`; `onError()` calls inside `onCaptureSuccess` and the
   sibling `onError(ImageCaptureException)` override are now dispatched via
   `mainExecutor.execute(onError)` again, since that code runs on
   `captureExecutor` (a background thread) and mutates Compose state, which
   must happen on the main thread. Note: this does *not* mirror
   `BarcodeScannerScreen.kt`'s executor lifecycle exactly, as originally
   claimed here - that screen creates its executor inside the same
   `DisposableEffect` that disposes it, so its creation and disposal scopes
   are identical by construction. This screen's `captureExecutor`/
   `textRecognizer` are created in a plain `remember` but disposed inside
   `DisposableEffect(previewView, lifecycleOwner)` - a narrower scope. See
   "Known limitations carried forward" below.
2. **`catch (e: Exception)` does not catch `OutOfMemoryError`** (an `Error`,
   not an `Exception`) - the single most likely throwable from decoding a
   full-resolution still, and exactly the failure mode fix #1 above makes
   more likely by keeping the decode on a background thread doing real work
   under memory pressure. Fixed by adding a second `catch (e: OutOfMemoryError)`
   clause alongside `catch (e: Exception)`, both routing to
   `mainExecutor.execute(onError)` so the retry prompt still surfaces
   instead of a silently stuck spinner. Also added a bounds-then-downsample
   decode pass (`BitmapFactory.Options.inJustDecodeBounds` to read
   dimensions, then `inSampleSize` via `calculateInSampleSize` to cap the
   decoded bitmap's long side at `MAX_DECODED_DIMENSION_PX = 2048`) to
   reduce how often `OutOfMemoryError` is hit in the first place, not just
   to catch it - ML Kit's text recognizer doesn't need more than a couple
   thousand pixels on the long side to read a nutrition panel's text. An
   earlier version of `calculateInSampleSize`'s loop condition only
   guaranteed the long side stayed under `2 x MAX_DECODED_DIMENSION_PX`
   (4096) - loose enough that the single most common phone photo resolution
   (~12 MP, e.g. 4000x3000) got no downsampling at all and still decoded a
   ~45 MB bitmap, undermining the point of the fix. Corrected the loop
   condition (`while (w > maxDimensionPx || h > maxDimensionPx)`, halving
   until both dimensions are truly at or under the target) and confirmed by
   hand for several concrete resolutions: 4000x3000 -&gt; 2000x1500 (sample 2,
   ~12 MB); 8160x6120 -&gt; 2040x1530 (sample 4, ~12 MB); 4096x3072 -&gt;
   2048x1536 (sample 2); 1600x1200 stays unchanged (sample 1, already under
   the target).
3. **The row-grouping tolerance fix from round 1 (median line height x 0.5)
   can chain-merge separate macro rows into one wrong reading.** A photo can
   pick up as much unrelated, much taller text (a title, an ingredients
   list) as there are lines in the macro table itself; once roughly half the
   recognized lines are tall outliers, a median-based estimate can tip onto
   them and widen the tolerance enough to merge every macro row into a
   single row, misreading a value from the wrong label entirely - a wrong
   number, not a fail-safe blank (CLAUDE.md rule #1 territory). Demonstrated
   and fixed in this round by switching from the median to the *minimum*
   recognized line height as the tolerance basis: the macro table's own text
   is normally the smallest, most tightly and consistently set text on the
   panel, so anchoring on the minimum keeps row spacing tied to the table
   regardless of how much larger unrelated text also appears in the frame.
   Locked in with a new test
   (`stillGroupsMacroRowsCorrectlyWhenHalfTheRecognizedLinesAreMuchTaller`)
   that reproduces the exact half-tall-lines shape that broke the median
   version; mutation-tested by hand (reverting to the median calculation
   makes this specific test fail with a `NullPointerException` on
   `result.calories!!`, confirming the test actually discriminates the two
   implementations rather than passing either way).
4. **One of round 1's new tests was vacuous.** The re-review found that
   `doesNotConfuseCommaPhrasedSaturatedFatOrSugarsWithTheTotalRow` (and the
   pre-existing dash-prefixed version) pass identically whether or not the
   `"saturated"`/`"sugar"` exclusion guards exist at all, because the total
   row's own value is always readable in those fixtures, so first-match-wins
   ordering already produces the right answer with or without the guard.
   Added two new tests instead
   (`leavesFatBlankRatherThanUsingTheSaturatedSubRowsValueWhenTheTotalRowsValueIsUnreadable`
   and the carbs/sugars equivalent) that make the total row's value
   unreadable, so the guard is the only thing preventing the sub-row's value
   from being misread as the total's - mutation-tested by hand (removing
   both guards makes these two tests fail, returning `6.1`/`18.5` instead of
   `null`, i.e. exactly the wrong-number failure mode the guards exist to
   prevent).

Re-verified against all 12 parser unit tests (9 from round 1 + 3 added by
this round) with a real `kotlinc` + JUnit run in this sandbox - all pass,
plus the three hand-run mutation checks above. As with round 1, the
parser-only fixes were independently compiled and run for real; the
CameraX/ML Kit executor and decode changes were verified only by careful
manual reading, not by a real build or device.

## Known limitations carried forward (not fixed, tracked here instead)

These fail safe - a limitation here means a blank field and a retry prompt,
never a wrong or invented number - so they were deliberately left as
follow-up rather than blocking this merge:

- **`captureExecutor` and `textRecognizer` are created in a plain `remember`
  but disposed inside `DisposableEffect(previewView, lifecycleOwner)`** - a
  narrower recomposition scope than the `remember`. If either key were ever
  invalidated (neither is expected to change today - `previewView` is itself
  `remember(context)` and `lifecycleOwner` is the nav back-stack entry's
  lifecycle), the effect would dispose and shut down the executor/recognizer
  while the outer `remember` kept holding the same, now-terminated instances,
  reproducing round 1's Critical-1 failure mode (a capture silently never
  completing) under a narrower trigger. Cheap fix if this ever becomes live:
  give `captureExecutor`/`textRecognizer` their own
  `DisposableEffect(Unit) { onDispose { ... } }` so creation and disposal
  scopes match exactly, the way `BarcodeScannerScreen.kt` already does for
  its own executor.
- **The minimum-line-height row-tolerance fix is fragile in the opposite
  direction from the median it replaced.** Round 1's median could be pulled
  wide by several tall outlier lines, over-merging rows. The minimum can be
  pulled tight by a single small stray bounding box (a speck, a stray
  punctuation mark) misrecognized as its own tiny line, under-merging a row
  that should have stayed together. This direction fails safe (a blank field
  and a retry prompt, never a wrong number) and is no worse than the
  original fixed-12px constant, but a more robust estimate (a low percentile
  instead of a strict minimum, or ignoring implausibly small bounding boxes)
  would reduce false retry prompts on real photos.
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

Both fix rounds' CameraX/ML Kit reasoning (executor dispatch timing, JPEG vs.
YUV image formats, the `ExperimentalGetImage` opt-in boundary) was checked
against prior knowledge of the CameraX `ImageCapture`/`ImageProxy` and ML Kit
`InputImage` API contracts, not against a live fetch of Google's
documentation site - `developer.android.com` and `developers.google.com` are
both network-blocked in this sandbox, so no URL below was actually retrieved
during this work. They're listed as the canonical source that should be used
to confirm this reasoning once real internet/device access is available,
per CLAUDE.md's evidence-discipline rule, not as evidence that was checked
here:

- [CameraX `ImageCapture` reference](https://developer.android.com/reference/androidx/camera/core/ImageCapture)
- [ML Kit Text Recognition v2 for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
