# Handoff Log

## Session Init
* **Last Updated:** 2026-11-03 | Initial Setup
* **Current State:** Baseline architecture initialized. `ResourceMonitor` (60% threshold guard) and `BurstCaptureManager` (disk-buffered 3-4 shots per spread) created.
* **Modified Files:**
  - `AGENTS.md`
  - `HANDOFF.md`
  - `app/src/main/java/com/eagertolearn/bookscanner/core/ResourceMonitor.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/core/BurstCaptureManager.kt`
* **Target for Next Agent:** 
  - **Gemini CLI:** Scaffold `CameraXPreviewView.kt` / activity capture UI, plus Gradle configuration (`build.gradle.kts`).
  - **Claude CLI:** Audit `BurstCaptureManager.kt` file lifecycles and thread safety across Coroutine scopes.

---

## Pass 1 — Gemini (Lead Architect & Scaffolder)
* **Last Updated:** 2026-11-03 01:13 EST | Gemini (Lead Architect & Scaffolder)
* **Current State:**
  - **Working Features:**
    - Complete Gradle project configuration (`settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, and updated `app/build.gradle.kts` with ViewBinding, WorkManager, Coroutines Play Services, and OpenCV 4.9.0).
    - Hardware-backed CameraX preview & spine-gutter alignment overlay (`CameraXPreviewView.kt`) + full activity capture UI & 60% resource intercept telemetry (`MainActivity.kt`, `activity_main.xml`, `AndroidManifest.xml`).
    - OpenCV native `Mat` / `Bitmap` lifecycle scoping extensions (`OpenCvExtensions.kt`) and disk-to-disk multi-shot spread stitcher & spine-gutter splitter (`StitcherEngine.kt`).
    - Polynomial baseline curvature estimator, cylindrical spine unwrapping via `Imgproc.remap()`, and LAB L-channel CLAHE enhancement (`DewarpFilter.kt`).
    - Searchable PDF volume assembler with ML Kit OCR text-layer embedding and 60% volume segmentation (`PdfVolumeAssembler.kt`).
    - Background `WorkManager` pipeline (`SpreadProcessingWorker.kt`, `LanFallbackSyncWorker.kt`) and Linux Python fallback receiver (`server/lan_fallback_server.py`).
  - **Stubs / Pending Audit:**
    - Concurrency/lifecycle audit on `BurstCaptureManager.kt` and native `Mat` deallocation paths in `StitcherEngine.kt` + PDF OCR coordinate transforms in `PdfVolumeAssembler.kt` (ready for Claude).
    - Polynomial baseline fitting & cylindrical `remap()` math verification in `DewarpFilter.kt` (ready for Grok).
* **Modified Files:**
  - `settings.gradle.kts`
  - `build.gradle.kts`
  - `gradle/libs.versions.toml`
  - `gradle.properties`
  - `app/build.gradle.kts`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/layout/activity_main.xml`
  - `app/src/main/java/com/eagertolearn/bookscanner/ui/CameraXPreviewView.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/ui/MainActivity.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/OpenCvExtensions.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/StitcherEngine.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/DewarpFilter.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/pdf/PdfVolumeAssembler.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/worker/SpreadProcessingWorker.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/worker/LanFallbackSyncWorker.kt`
  - `server/lan_fallback_server.py`
  - `HANDOFF.md`
* **Target for Next Agent:**
  - **Claude (Auditor & Safety Guard):** Run static review against `git diff`. Audit `BurstCaptureManager.kt` file lifecycles and thread safety across Coroutine scopes, audit `StitcherEngine.kt` and `DewarpFilter.kt` for native `Mat` memory leaks / OOM vectors, and verify PDF text-layer coordinate accuracy in `PdfVolumeAssembler.kt`.
  - **Grok (Math & Computer Vision Specialist):** Follow Claude's audit to review and optimize polynomial baseline unrolling, spine-gutter detection formulas, and cylindrical projection `remap()` matrix math in `DewarpFilter.kt` and `StitcherEngine.kt`.


---

## Pass 2 — Claude (Auditor & Safety Guard)
* **Last Updated:** 2026-11-03 01:35 EST | Claude (Auditor & Safety Guard)
* **Build:** `JAVA_HOME=~/android-studio/jbr ./gradlew assembleDebug` → **BUILD SUCCESSFUL**, `lintDebug` 0 errors. Not yet run on a device (no device attached).
  - Toolchain moved to what is installed locally: Gradle wrapper 9.6.0, AGP 9.4.1 (built-in Kotlin; `kotlin-android` plugin and `kotlinOptions` removed), JDK 25 (JBR) running, bytecode target 17. Compile/Target SDK stays **34**, min 28 (AGP auto-installed platform 34). `app/build.gradle.kts` now uses the version catalog.
* **User decision (2026-11-03):** the 60% storage rule means **60% of the free space recorded when the current volume started**, not total device capacity. Heap rule unchanged (60% of max heap).
* **Fixed (audit lane):**
  - `PdfVolumeAssembler`: saved over the PDF it was lazily reading from (corruption) → temp file + rename. Every page past a persistent breach rolled to a new volume → single roll point via `ScanSessionStore.rollVolume()`. OCR bitmap now decoded once and `recycle()`d. Text layer: font size from Helvetica ascent/descent, baseline offset by descent, horizontal stretch to box width.
  - Workers: concurrent `process_spread_N` jobs raced on the same PDF and stacked ~400MB native pipelines → one sequential `APPEND_OR_REPLACE` chain (`SpreadProcessingWorker.enqueue`). Retries capped at 3; unrecoverable spreads return flagged success so the chain isn't failed. OpenCV/PDFBox are now initialized inside the worker (`NativeLibs`), since WorkManager can run after process death without the Activity.
  - `BurstCaptureManager`: unsynchronized list mutated from Default + Main; force-complete during an in-flight shot put files in the wrong spread → main-thread confinement, in-flight guard, callbacks on main executor. Completed spreads delivered via `onSpreadReady` callback (StateFlow conflation could drop them). Spread index persisted, so `spread_N` dirs and work names no longer collide after restart.
  - `ResourceMonitor` + new `ScanSessionStore` (SharedPreferences): persisted volume part, free-space baseline, next spread index. Capture is no longer blocked at 60% (would deadlock with nothing queued to roll); only blocked when <256MB would remain.
  - `MainActivity`: removed the breach handler that bumped the volume separately and uploaded *all* `spread_*` dirs (including in-progress/queued ones being read by workers).
  - `StitcherEngine`: leaked ORB mask `Mat()`s released; `Imgproc.accumulate` replaces the per-shot CV_32FC3 temp (~144MB at 12MP); `imwrite` results checked (also in `DewarpFilter`).
  - `server/lan_fallback_server.py`: `cgi` was removed in Python 3.13 (host has 3.14) → stdlib `email` multipart parser; path traversal via `book_name`/`spread_dir`/filename fixed; body size cap. Smoke-tested locally.
* **Behavior to confirm with user:** on a breach with LAN enabled, the worker rolls the volume and offloads only *that* spread's raw shots; the LAN server doesn't build PDFs, so offloaded pages are missing from the local volumes.
* **Modified Files:** `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew*`, `.gitignore`, `core/ScanSessionStore.kt` (new), `core/NativeLibs.kt` (new), `core/ResourceMonitor.kt`, `core/BurstCaptureManager.kt`, `pdf/PdfVolumeAssembler.kt`, `vision/StitcherEngine.kt`, `vision/DewarpFilter.kt`, `worker/SpreadProcessingWorker.kt`, `ui/MainActivity.kt`, `server/lan_fallback_server.py`, `HANDOFF.md`.
* **Target for Next Agent:**
  - **Grok (Math & CV):** `DewarpFilter.kt` — polynomial fit from 3 centroid stations, cylindrical shift direction/magnitude, `remap` mapY radial-scale formula. `StitcherEngine.kt` — mean fusion does not suppress glare (consider median/min-of-specular), black `warpPerspective` borders are averaged into edges, ORB on full-res 12MP is slow (detect on a downscale, scale H), gutter = darkest column in 35–65% band is fragile.
  - **Gemini (Scaffolder):** add `ndk { abiFilters += "arm64-v8a" }` (APK is 166MB); OpenCV 4.9.0 `.so`s are not 16KB-aligned (needed for targetSdk 35+); long spreads may exceed WorkManager's 10-min window → `setForeground`; book-name UI (hardcoded "BookScan"); orphaned partial-spread shots in cache after app restart; replace deprecated `setTargetAspectRatio` with `ResolutionSelector`; LAN URL is hardcoded `192.168.1.100`.

---

## Pass 2b — Claude (second audit, pre-handoff)
* **Last Updated:** 2026-11-03 | Claude (Auditor & Safety Guard)
* **Build:** `assembleDebug` + `lintDebug` → **BUILD SUCCESSFUL**, 0 lint errors. Still not run on a device.
* **Fixed:**
  - Heap-only breach rolled a new volume on every page (rolling frees no heap) → `ScanSessionStore.resolveVolumePart()` rolls on storage breach always, on heap breach once per episode (latched until heap recovers).
  - Facing pages could straddle a volume boundary → volume resolved once per spread in the worker, passed to the assembler.
  - Retry after left-page commit duplicated pages → `PdfVolumeAssembler.appendSpreadWithOcr()` commits both pages in one atomic save, with the last committed spread index stored in the PDF's document info. The worker also checks `lastCommittedSpreadIndex` and skips reruns.
  - Raw shots moved from `cacheDir` to `noBackupFilesDir/spreads` (the OS can purge cacheDir under storage pressure while shots wait for a network-constrained LAN upload).
  - A partially captured spread is resumed after Activity recreation or process death (`activeSpreadIndex` persisted, files reloaded on init). Previously it was orphaned. Spreads are enqueued *before* the index advances, so a crash duplicates, which the idempotency check drops, rather than losing.
  - `CancellationException` is rethrown from the worker instead of burning retry attempts.
* **Modified Files:** `core/ScanSessionStore.kt`, `core/ResourceMonitor.kt`, `core/BurstCaptureManager.kt`, `pdf/PdfVolumeAssembler.kt`, `worker/SpreadProcessingWorker.kt`, `HANDOFF.md`.
* **Target for Next Agent (additions to Pass 2 list):**
  - **Grok:** `DewarpFilter.estimateBaselinePolynomial`: the quadratic is evaluated in s=1-u but a2=(dS+dO)/2, a1=dO-dS don't interpolate the stations (P(0.5) ≠ 0), and the offset is applied as a radial *scale* `normY*(1+offset*normY)` rather than a baseline displacement. `applyCylindricalPolynomialRemap`: per-pixel Kotlin loop recomputes `pow` per row. Precompute per column and build the maps with Core ops. `StitcherEngine`: black `warpPerspective` borders are accumulated, yet every pixel is divided by `fusedCount` (needs a per-pixel weight mask).
  - **Gemini / architecture:** each spread append loads and rewrites the whole volume (O(n²) I/O over a volume, plus a transient second copy that counts against the storage budget). Consider per-spread PDFs merged at volume close. The Pass 2 "orphaned partial-spread shots" item is resolved.

---

## Pass 3 — Grok (Math & Computer Vision)
* **Last Updated:** 2026-11-03 02:04 EST | Grok (Math & Computer Vision Specialist)
* **Build:** `testDebugUnitTest` → 9 tests, all passing (`PageGeometryTest`). `assembleDebug` → **BUILD SUCCESSFUL**. Geometry was checked on synthetic profiles (valley vs dark figure, rule, flat page). Remap and fusion have not been run on a real page image (no device, no host OpenCV).
* **Review findings, now fixed:**
  - **Baseline polynomial.** `a2 = (dS+dO)/2`, `a1 = dO-dS` does not pass through the three stations, and `P(0.5) ≠ 0`. The curve was also evaluated at `s = 1-u`, so the spine station and the outer station were swapped. `estimateBaselinePolynomial` now solves the Vandermonde system in spine distance `u` (`0` at the spine). δ is a fraction of image height: centroid shift inside the middle band, times `bandHeight/rows`, clamped to ±4% (spine) and ±2% (outer edge).
  - **mapY.** The centroid is a vertical translation of the text. It was applied as `normY * (1 + offset * normY)`, a scale about the page center, which moves the top and bottom in opposite directions. `mapY = y + δ(u_source) · (height − 1)`, clamped to ±6% of height. Column source-X and δ are computed once; `mapX`/`mapY` are built with `Core.repeat` plus a per-row scalar add (no per-pixel `pow`).
  - **Cylindrical mapX.** `u - 0.14*(1-cos(…))` had `d(source)/d(dest) > 1` at the spine, so the gutter was compressed. Source position is now the orthographic integral `∫ cos θ ds` over the inner 25% of the page, normalized so both edges stay fixed. Spine tilt is 0.521 rad (~29.8°), which expands the gutter by 1.14× (the old 0.14 factor, with the sign corrected). `α = 0` is the identity.
  - **Fusion.** Black `warpPerspective` borders were divided by `fusedCount`. Each shot now carries a nearest-neighbor coverage mask (eroded 1px) and `Imgproc.accumulate` uses it. The result is divided by a per-pixel weight. Glare is min-of-specular: a pixel that reaches gray 245 in one shot and is ≥20 levels darker in another is replaced with the darkest in-mask color, unless that mask covers more than 35% of the frame (exposure change, leave the mean). Full-frame median was skipped so a 4-shot 12MP burst does not keep four resident images.
  - **ORB.** Detect and match on a long edge of 960px. Points are scaled back to full resolution before RANSAC (4px threshold stays in full-res pixels), with Lowe's ratio, an inlier count, and a plausibility check (scale, translation, perspective) so a bad match is dropped and the base shot stands.
  - **Gutter.** Darkest column in the 35–65% band loses to rules, figures, and wide black regions. `PageGeometry.spineGutter` scores shoulder prominence of a narrow valley (0.4%–6% of width, at least 12 gray levels) on the central 70% of rows. The half-depth core of the valley is left out of both pages. A flat or ambiguous profile stays at the center with no inset.
* **Assumptions (correct these if the books disagree):**
  - Spine angle is a constant 29.8°, not estimated from the photo. Flatter books will be slightly over-stretched at the gutter; tighter gutters will be under-stretched.
  - Baseline curl is one shift per column. The three-station centroid cannot see a curl that changes with row.
  - Specular test is saturation plus cross-shot range, not a learned highlight detector.
* **Modified Files:**
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/PageGeometry.kt` (new)
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/DewarpFilter.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/StitcherEngine.kt`
  - `app/src/test/java/com/eagertolearn/bookscanner/vision/PageGeometryTest.kt` (new)
  - `app/build.gradle.kts`
  - `gradle/libs.versions.toml`
  - `HANDOFF.md`
* **Target for Next Agent:**
  - **Claude (Auditor):** Re-audit native `Mat` lifetimes in the new fusion path (`StitcherEngine.accumulateAlignedShots`, coverage mask, `knnMatch` `MatOfDMatch`s, darkest / min / max mats) and the per-row `mapY.row()` headers in `DewarpFilter.applyCylindricalPolynomialRemap`. Peak native set is still one CV_32FC3 accumulator plus the 8-bit darkest image and two gray planes. ORB / `DescriptorMatcher` are released only by finalizer.
  - **Gemini (Scaffolder), still open from Pass 2 / 2b:** `ndk { abiFilters += "arm64-v8a" }` (APK ~166MB); OpenCV 4.9.0 `.so`s are not 16KB-aligned (targetSdk 35+); long spreads may exceed WorkManager's 10-minute window → `setForeground`; book-name UI (hardcoded "BookScan"); replace deprecated `setTargetAspectRatio` with `ResolutionSelector`; LAN URL is hardcoded `192.168.1.100`; each spread append rewrites the whole volume (O(n²) I/O plus a transient second copy against the storage budget) — consider per-spread PDFs merged at volume close. Orphaned partial-spread shots are already fixed.

---

## Pass 4 — Claude (re-audit of Grok Pass 3)
* **Last Updated:** 2026-11-03 | Claude (Auditor & Safety Guard)
* **Build:** `testDebugUnitTest` (9/9), `assembleDebug`, `lintDebug` → **BUILD SUCCESSFUL**. Still not run on a device or on a real page image.
* **Mat lifetimes in the Pass 3 code: clean.** Every `Mat` in `accumulateAlignedShots` / `alignToBase` / `updateSpecularReferences` / `replaceSpecularWithDarkest` is released on all paths, including `continue` and early `return`. The `knnMatch` `MatOfDMatch` list is released. The OpenCV 4.9 binding releases its own temporary Mat (checked with `javap`). The `mapY.row(y)` headers are released per row. ORB / `DescriptorMatcher` have no `release()` in the Java API (only `clear()` + finalizer). Their native footprint is KBs, so they are left as is.
* **Fixed:**
  - `StitcherEngine.divideByWeight` was the real native peak, not the accumulator. A full `split` + float `merge` held 3×CV_32FC1 + a second CV_32FC3 while the base frame was still resident. At 12MP the peak was **~620MB native**. It now handles one float plane at a time (`extractChannel` → divide → 8-bit), and `baseMat` / `accumulator` / `weight` / specular refs are released as soon as they are consumed. The stitch peak is now in the alignment loop at **~360MB**. `baseGray` is freed after the downscale, and each candidate frame is freed right after its warp.
  - `DewarpFilter.applyCylindricalPolynomialRemap`: the full-frame `mapX`/`mapY` were allocated before the `try`. An OOM `CvException` on the second leaked the first. They are now allocated inside the `try`.
* **Math findings for Grok (checked numerically, not changed):**
  - **Baseline centroid aliases at half a line pitch.** The centroid of |Sobel Y| in a fixed row band only tracks a vertical text shift up to ~½ line pitch (~0.5% of page height at typical pitch). Beyond that it wraps (synthetic pitch 47px: true 1.0% H → measured −0.18%; true 3.0% → +0.06%). The ±4% clamp lets the estimator report values well past the range it can measure, so real gutter sag of 1–3% comes out as noise or the wrong sign. Suggest 1-D cross-correlation of the spine-strip row profile against the mid-strip profile, or tracking per-line baselines.
  - **Wide gutter shadows fall back to center.** With a Gaussian shadow at 0.52·W, `spineGutter` finds it for σ ≤ 2% of spread width but returns the 0.50 fallback for σ = 3% (depth 30) and σ = 5% (any depth), which is a thick-book gutter. Cause: the shoulder window (`width/40` = 2.5%) lands on the valley slope, and `maxWidth` = 6%. An 80px miss at 4000px leaves the shadow on one page.
  - **Fusion ghosting risk (needs a real-image test).** One homography cannot register a curled page, so mean fusion may double text near the gutter. The specular rule (max ≥ 245, range ≥ 20) also fires on misaligned text edges when paper exposes near-white, which replaces them with the darkest shot and bolds or ghosts the text.
  - Minor: `estimateBaselinePolynomial` runs Sobel on the full page to read three 16px strips. Run it on the strip ROIs instead.
* **Modified Files:** `vision/StitcherEngine.kt`, `vision/DewarpFilter.kt`, `HANDOFF.md`.
* **Target for Next Agent:**
  - **Grok:** the three math findings above. The baseline aliasing is the priority, because the dewarp currently acts on measurement noise for most real sags. Add a `PageGeometryTest` case for a wide (σ ≥ 3%) gutter.
  - **Gemini:** the Pass 3 open list is unchanged (ABI filter, 16KB alignment, `setForeground`, book-name UI, `ResolutionSelector`, LAN URL, O(n²) volume rewrite).
