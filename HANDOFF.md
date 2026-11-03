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

---

## Pass 5 — Grok (Math & Computer Vision)
* **Last Updated:** 2026-11-03 02:40 EST | Grok (Math & Computer Vision Specialist)
* **Build:** `testDebugUnitTest` → 15 tests, all passing (`PageGeometryTest`). `assembleDebug` → **BUILD SUCCESSFUL**. Still synthetic profiles only. Remap and fusion have not been run on a real page image (no device, no host OpenCV).
* **Baseline aliasing, fixed.** The centroid of a periodic strip wraps past half a line pitch, so a 1–3% sag was noise or the wrong sign. `PageGeometry.verticalShiftPx` correlates the text-block envelopes first (top and bottom margins are the anchor), then refines the lag inside ±0.45 pitch on the high-passed row profiles. Search range is ±6% of page height. A flat strip, a full-bleed strip with no margin, or an ambiguous envelope returns 0. `DewarpFilter.estimateBaselinePolynomial` reduces three full-height gray strips (no full-page Sobel) and clamps the spine station to ±4% and the outer station to ±2%. Checked past half a pitch and at one full pitch (48px on a 1600px page, pitch 47), including a gain/offset change.
* **Wide gutter, fixed.** Shoulders are the uphill walk, not a fixed `width/40` window, so a wide shadow is scored against the page instead of its own slope. Valleys up to 16% of the spread width are allowed. A run wider than 6% whose floor occupies more than 75% of the half-depth width is still a figure and is rejected. The excluded core stays the half-depth run, capped at 8% of the width on each side. A Gaussian at 0.52·W is found for σ = 3% and σ = 5% at depths 20 and 30. The narrow-valley, rule, flat, and dark-block tests still pass.
* **Fusion ghosts.** `PageGeometry.fusionChoice` is the per-pixel rule, applied in bulk by `StitcherEngine.resolveBurstConflicts`. Highlight: darker shot still paper (gray > 180), max > 245, lift in (20, 90]. Those pixels take the darkest shot after a 5×5 opening, and only if that mask is ≤ 35% of the frame. Ink against paper (min ≤ 140 and range > 30) keeps the base shot, so a curled-page mismatch is not averaged and not stamped darker. The band between those two (antialiased gray, or cream paper under a hard clip) stays the weighted mean. Not checked on a photograph.
* **Assumptions:**
  - The strip must include the text block's top or bottom margin. A full-bleed page is left unshifted.
  - Line pitch is the first autocorrelation peak above 0.25, between 7px and 10% of the strip.
  - Spine tilt is still a constant 29.8°.
  - A flat dark band wider than 6% of the spread is a figure, even when it sits on the spine.
* **Modified Files:**
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/PageGeometry.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/DewarpFilter.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/StitcherEngine.kt`
  - `app/src/test/java/com/eagertolearn/bookscanner/vision/PageGeometryTest.kt`
  - `HANDOFF.md`
* **Target for Next Agent:**
  - **Claude (Auditor):** `StitcherEngine.resolveBurstConflicts` — two 8-bit masks, the range plane, and the 5×5 kernel, including the `pixels <= 0` return. `baseMat` is now released after that call, so `divideByWeight` runs with one extra CV_8UC3 still resident. The alignment loop should still be the native peak. ORB / `DescriptorMatcher` are unchanged (finalizer only).
  - **Gemini:** the Pass 3 open list is unchanged (ABI filter, 16KB alignment, `setForeground`, book-name UI, `ResolutionSelector`, LAN URL, O(n²) volume rewrite).

---

## Pass 6 — Claude (re-audit of Grok Pass 5)
* **Last Updated:** 2026-11-03 02:50 EST | Claude (Auditor & Safety Guard)
* **Build:** `testDebugUnitTest` (15/15), `assembleDebug`, `lintDebug` (0 errors) → **BUILD SUCCESSFUL**. Still not run on a device or on a real page image.
* **Mat lifetimes in `resolveBurstConflicts`: clean.** `range`, `maskA`, `maskB`, and the 5×5 kernel are released on every path, including the `pixels <= 0` return. The BASE and DARKEST masks cannot overlap: BASE needs min ≤ 140 and DARKEST needs min > 180, and the opening only removes pixels. So applying BASE first matches `PageGeometry.fusionChoice`.
* **Fixed:**
  - **Native peak moved to `divideByWeight`.** Pass 5 keeps `baseMat` resident through the division. At 12MP, the persistent set (accumulator 144 + weight 12 + darkest 36 + min/max gray 24 + base 36 ≈ 252MB) plus the full-frame CV_32FC1 weight (48), the CV_32FC1 plane (48), and three 8-bit planes (36) peaked at **~384MB**, above the alignment loop. The division now runs in place in 256-row strips and writes straight into a preallocated `fusedBgr`. Strip temporaries are ~16MB, so this step is ~304MB. The accumulator is consumed and released right after, as before.
  - `alignToBase`: the full-res `candidateGray` is released right after the downscale instead of being held through both `warpPerspective` calls. The alignment-loop peak drops from ~360MB to **~348MB**, and that loop is the stitch peak again.
* **Math finding for Grok (checked against the code, not changed):**
  - **Glare over ink falls to BASE.** If glare washes out text in one shot, that pixel has min ≈ ink (≤ 140) and max ≥ 245. `fusionChoice` returns BASE (misalignment), not DARKEST. When the base shot (`shotFiles.first()`) is the glared one, the glare is kept over the text, and that is the case the burst exists to fix. `fusionChoice(40f, 250f) == BASE`. A possible split: if the base pixel itself is ≥ `SPECULAR_GRAY`, the base is the clipped shot, so take the darkest (or the mean of the unclipped shots) instead of base. That needs a base-gray plane in `resolveBurstConflicts` (+12MB at 12MP, still below the peak). Add a `PageGeometryTest` case.
  - Minor: `DewarpFilter.estimateBaselinePolynomial` checks `rowMeans.get(...) < rows`, but the OpenCV Java `get` returns **bytes** copied, not elements. A partial copy of more than rows/4 floats passes. This can't happen with a `rows×1` reduce output, so it is harmless, but the check should be `< rows * 4` or be removed.
* **Modified Files:** `vision/StitcherEngine.kt`, `HANDOFF.md`.
* **Target for Next Agent:**
  - **Grok:** glare-over-ink in `fusionChoice` / `resolveBurstConflicts` (above). If you add a base-gray plane, release it in the same `finally`. Keep `divideByWeight` strip-wise. A full-frame float temporary there pushes the peak above the alignment loop again.
  - **Gemini:** the Pass 3 open list is unchanged (ABI filter, 16KB alignment, `setForeground`, book-name UI, `ResolutionSelector`, LAN URL, O(n²) volume rewrite).

---

## Pass 7 — Grok (markdown check)
* **Last Updated:** 2026-11-03 03:01 EST | Grok (Math & Computer Vision Specialist)
* **Scope:** `AGENTS.md` and `HANDOFF.md` against the current sources. No vision code changed. Tests were not re-run.
* **Corrected:** `AGENTS.md` still said to segment when total device storage or heap exceeds 60%, and to dump raw batch queues. The Pass 2 user decision and the code measure storage against `ScanSessionStore.baselineFreeBytes`, roll a heap-only breach once per episode (`resolveVolumePart`), keep capturing until fewer than 256MB would remain (`canAcceptBurst`), warn in the UI, and offload only the spread about to be processed (`SpreadProcessingWorker` passes that spread's directory to `LanFallbackSyncWorker`).
* **Checked, still accurate:**
  - Pass 6 glare note. `fusionChoice(40f, 250f)` is `BASE` (`min ≤ 140` wins over a saturated max). `resolveBurstConflicts` has no base-gray plane. `misalignedInkKeepsTheBaseShot` locks that result in. The split Pass 6 suggested is still open.
  - Pass 6 `Mat.get` note. OpenCV 4.9.0 `mat_copy_data` returns `count * sizeof(T)` bytes (`modules/java/generator/src/cpp/Mat.cpp`). `estimateBaselinePolynomial` still compares that to `rows`. A full `rows×1` CV_32F copy returns `rows * 4`, so the check does not fire on the success path.
  - Pass 6 mask note. BASE is `min ≤ 140`, DARKEST is `min > 180`. The 5×5 opening only removes pixels. Strip division is still 256 rows inside `divideByWeight`, with `fusedBgr` created there via `dst8.create` before the strip loop.
  - Pass 5 geometry. Stations, ±4%/±2% clamps, ±6% search, cylindrical `u`, wide-valley limits (16% width, 6% flat-floor reject, 8% inset cap), and the 15 `PageGeometryTest` cases match the sources.
  - Gemini open list. No `abiFilters`, `setForeground`, book-name field, or `ResolutionSelector`. `setTargetAspectRatio` and `http://192.168.1.100:8085/...` are still hardcoded. Each spread still rewrites the volume. The debug APK on disk is **159MB** compressed and still packages arm64-v8a, armeabi-v7a, x86, and x86_64 (Pass 2's 166MB was the earlier package).
* **Modified Files:** `AGENTS.md`, `HANDOFF.md`.
* **Target for Next Agent:**
  - **Grok:** unchanged from Pass 6. Glare-over-ink in `fusionChoice` / `resolveBurstConflicts`. If you add a base-gray plane, release it in the same `finally`. Keep `divideByWeight` strip-wise. The `rowMeans.get` check should be `< rows * 4` or be removed.
  - **Gemini:** unchanged open list (ABI filter, 16KB alignment, `setForeground`, book-name UI, `ResolutionSelector`, LAN URL, O(n²) volume rewrite). Current debug APK is 159MB compressed across four ABIs.

---

## Pass 8 — Gemini (Lead Architect & Scaffolder)
* **Last Updated:** 2026-11-03 05:17 EST | Gemini (Lead Architect & Scaffolder)
* **Build:** `testDebugUnitTest` (15/15), `assembleDebug`, `lintDebug` (0 errors) → **BUILD SUCCESSFUL**. Debug APK size reduced from **159MB** to **52MB** (`arm64-v8a` only). `server/lan_fallback_server.py` PDF assembly smoke-tested on host Python 3.14.
* **Current State:**
  - **Working Features (resolved Gemini open list from Passes 2–7):**
    - **ABI Filter:** Added `ndk { abiFilters += "arm64-v8a" }` in `app/build.gradle.kts`, shrinking the packaged APK from 159MB (4 ABIs) to 52MB. Note: `org.opencv:opencv:4.9.0` prebuilt `libc++_shared.so` triggers `Aligned16KB` lint warning (4KB ELF segment alignment, acceptable on `targetSdk = 34` per `AGENTS.md`).
    - **CameraX `ResolutionSelector`:** Replaced deprecated `setTargetAspectRatio(AspectRatio.RATIO_4_3)` in `CameraXPreviewView.kt` with `ResolutionSelector` (`AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY`).
    - **Per-Spread PDF Staging & O(n) Volume Merge (`PdfVolumeAssembler.kt`):** Eliminated the `O(n²)` full-volume rewrite and 2× temp file storage spike on every spread. `appendSpreadWithOcr` now writes a self-contained 2-page searchable PDF (`volumes/[BookName]_PartXX_spreads/spread_XXXX.pdf`) per spread (`O(1)` I/O), and `finalizeVolume` / `finalizeAllPendingVolumes` merges staged spreads into `volumes/[BookName]_PartXX.pdf` in a single `O(n)` pass via `PDFMergerUtility` (`MemoryUsageSetting.setupTempFileOnly`) on volume roll, session close (`MainActivity.onStop`), or user request.
    - **Foreground WorkManager Execution (`SpreadProcessingWorker.kt`, `AndroidManifest.xml`):** Added `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` permissions, merged `foregroundServiceType="dataSync"` onto `SystemForegroundService`, and implemented `setForeground` / `getForegroundInfo` so long multi-spread bursts are not cut off by WorkManager's 10-minute background execution window. Also added `SpreadProcessingWorker.enqueueFinalize` on the same sequential `UNIQUE_PIPELINE_NAME` work chain and wired `KEY_LAN_SERVER_URL` through to `LanFallbackSyncWorker`.
    - **Session & Book Settings UI (`MainActivity.kt`, `ScanSessionStore.kt`, `BurstCaptureManager.kt`, `activity_main.xml`, `strings.xml`):** Added a compact Session & Book Settings dialog to configure `bookName`, `lanServerUrl`, and trigger `New Book / Reset` (which enqueues volume finalization for the previous session and resets counters). Fixed `BurstCaptureManager.CaptureState.Idle` so the telemetry bar accurately reflects the current spread index and captured shot count (`1/4`, `2/4`, `3/4`) between burst shots and across restarts.
    - **LAN Fallback Server PDF Assembly (`server/lan_fallback_server.py`):** Added `assemble_volume_pdf` and `_parse_jpeg_header` to embed enhanced spread JPEGs directly into `lan_fallback_output/[BookName]_PartXX.pdf` via PDF 1.4 `/DCTDecode` XObjects (atomic `.tmp` + rename, natural spread sorting, zero third-party PDF dependencies).
  - **Pending / Stubs:**
    - Vision/math items from Pass 6/7 remain untouched in `PageGeometry.kt`, `StitcherEngine.kt`, and `DewarpFilter.kt` per user directive (ready for Grok).
* **Modified Files:**
  - `app/build.gradle.kts`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/layout/activity_main.xml`
  - `app/src/main/java/com/eagertolearn/bookscanner/core/ScanSessionStore.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/core/BurstCaptureManager.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/ui/CameraXPreviewView.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/ui/MainActivity.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/pdf/PdfVolumeAssembler.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/worker/SpreadProcessingWorker.kt`
  - `server/lan_fallback_server.py`
  - `HANDOFF.md`
* **Target for Next Agent:**
  - **Grok (Math & CV):** Resolve glare-over-ink in `PageGeometry.fusionChoice` / `StitcherEngine.resolveBurstConflicts` (when `minGray <= 140` and `maxGray > 245`, check whether the base shot itself is specularly clipped `>= SPECULAR_GRAY` and select `DARKEST` instead of `BASE`; release any added base-gray `Mat` in `finally` and keep `divideByWeight` strip-wise) + add a `PageGeometryTest` case. Also fix or remove the `rowMeans.get(0, 0, profiles[idx]) < rows` byte-vs-float check in `DewarpFilter.estimateBaselinePolynomial`.
  - **Claude (Auditor):** Audit `PdfVolumeAssembler.finalizeVolume` (`PDFMergerUtility` temp-file lifecycle, idempotency across partial merges, and staging cleanup), `SpreadProcessingWorker` (`enqueueFinalize` ordering on `UNIQUE_PIPELINE_NAME` and `setForeground` handling), `ScanSessionStore.resetSession` / `BurstCaptureManager.resetForNewSession` thread safety, and `server/lan_fallback_server.py` `assemble_volume_pdf`.

---

## Pass 9 — Grok (Math & Computer Vision)
* **Last Updated:** 2026-11-03 05:27 EST | Grok (Math & Computer Vision Specialist)
* **Build:** `testDebugUnitTest` → 16 tests, all passing (`PageGeometryTest`). `assembleDebug` → **BUILD SUCCESSFUL**. Still synthetic profiles only. Remap and fusion have not been run on a real page image (no device, no host OpenCV).
* **Glare over ink, fixed.** `fusionChoice(40, 250)` was `BASE` for every reference pixel, so a clipped base shot kept the glare and threw away the ink in another shot. The rule now takes `baseGray`. Ink against a brighter sample stays `BASE` when the reference is the ink (`baseGray` 40 or 200) or is only at the specular floor with nothing brighter (`baseGray` 245 and `maxGray` 245). It returns `DARKEST` when `minGray <= 140`, `maxGray > 245`, and `baseGray >= 245` (`40/250` with base 250, `40/255` with base 245, `100/250` with base 248). Paper glare and the mean band are unchanged, including cream paper at 160 under a clip of 255.
* **Masks.** `StitcherEngine.resolveBurstConflicts` builds that split before the paper-highlight mask. The ink mask is copied from the base shot, then the clipped-base subset is overwritten from the darkest shot. That subset is not opened and is not gated by the 35% fraction: a 5×5 open would delete the strokes, and a page of washed text can exceed 35%. The 5×5 opening and the 35% exposure gate stay on the paper-highlight mask only. One CV_8UC1 `baseGray` is allocated inside the `try` and released in the same `finally`, including the `pixels <= 0` return. After the luminance is read it is reused as the `max > 245` mask, so there is no third full-frame mask. `divideByWeight` is still 256-row strips with no new full-frame float.
* **`Mat.get` byte count.** `estimateBaselinePolynomial` now treats a short copy as failure when `get` returns fewer than `rows * Float.SIZE_BYTES` bytes. A full CV_32F column (`rows * 4`) is accepted. OpenCV 4.9 `mat_copy_data` returns bytes.
* **Assumptions:**
  - A clipped reference pixel has no remaining information. If the darkest shot has ink there because of curl rather than because the glyph belongs there, that ink is still written.
  - `baseGray <= maxGray`, because the max is seeded from the reference shot.
  - Gray 245 with no brighter sample is not specular. The paper-glare test is strict `>`.
* **Modified Files:**
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/PageGeometry.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/DewarpFilter.kt`
  - `app/src/main/java/com/eagertolearn/bookscanner/vision/StitcherEngine.kt`
  - `app/src/test/java/com/eagertolearn/bookscanner/vision/PageGeometryTest.kt`
  - `HANDOFF.md`
* **Target for Next Agent:**
  - **Claude (Auditor):** `StitcherEngine.resolveBurstConflicts` now holds `baseGray` (CV_8UC1) and reuses it as a mask after the luminance threshold. Both `darkest.copyTo` calls, the `pixels <= 0` return, and the `finally` releases. Confirm the ink-recovery mask cannot be overwritten by the later paper-highlight `darkest.copyTo` (min ≤ 140 vs min > 180). `divideByWeight` must stay strip-wise. Peak should remain the alignment loop: `baseGray` exists only after the accumulator and weight are released.
  - **Gemini:** the Pass 3 open list was cleared in Pass 8. No new scaffolding item from this pass.

---

## Pass 10 — Claude (Auditor, final pass over Passes 7–9)
* **Last Updated:** 2026-11-03 05:43 EST | Claude (Auditor & Safety Guard)
* **Build:** `testDebugUnitTest` 19/19 (16 `PageGeometryTest`, 3 new `PdfMergeTest`), `assembleDebug`, `lintDebug` (0 errors) → **BUILD SUCCESSFUL**. Debug APK 53MB. Server assembler smoke-tested with a byte-exact xref check (no PDF reader on host). Still nothing has run on a device.
* **Fixed:**
  - **Volume merge OOM (`PdfVolumeAssembler.finalizeVolume`).** In pdfbox-android 2.0.27, `PDFMergerUtility` defaults to `PDFBOX_LEGACY_MODE` (checked against the bytecode). That mode loads every source through `RandomAccessBufferedFileInputStream` (a 1000 × 4KB page cache each) and keeps all of them open until `save`. Heap was about 4MB per staged spread, so a 100-spread volume needed ~400MB and threw `OutOfMemoryError`, which `catch (Exception)` does not catch. The process crashed, and the finalize work at the head of the chain crashed it again on every restart. `OPTIMIZE_RESOURCES_MODE` closes each source but ignores `destinationDocumentInformation`, which would lose `BookScannerLastSpread`. New `pdf/PdfMerge.kt` runs that mode's page loop itself. `PDFCloneUtility` deep-copies streams, so each source closes right after its pages are cloned, and the doc info is set directly.
  - **Merge disk spike.** A merge needs ~2× the merged size free (destination scratch plus output) while the sources still exist. After a storage-breach roll, about 40% of the baseline remains against a ~60% volume, so the merge ran out of space partway through. `finalizeVolume` now compares `PdfMerge.requiredFreeBytes` (2 × inputs + 64MB) with `ScanSessionStore.currentFreeBytes()` and defers if short. The staged spreads stay, and `finalizeAllPendingVolumes` retries later. A stale `_PartXX.pdf.tmp` is deleted before a merge starts.
  - **Roll-time merge failure cost a spread its attempts (`SpreadProcessingWorker`).** The roll is persisted before the merge, so a retry never came back to the merge, but the exception still used one of the spread's three attempts. That merge is now wrapped in try/catch and logged.
  - **Session reset data loss (`MainActivity`, `ScanSessionStore.resetSession`, `BurstCaptureManager`).** Three paths:
    1. Spreads still queued from the old session ran after the reset. Each read the *new* session's volume part (old book name, wrong part) and called `markSpreadCommitted(oldIndex)` on the reset counter. The new session's spreads `1..oldIndex` were then skipped as already committed, and their raw shots deleted.
    2. Resetting to a name that already had `_Part01.pdf` (including "New Book / Reset" with the same name) made `appendSpreadWithOcr` report new spreads up to that volume's last-spread marker as `alreadyCommitted`. Their raw shots were deleted, and later spreads were appended to the old book's file.
    3. A reset with a shot in flight reset the store but not `BurstCaptureManager.spreadIndex`.

    Now `startNewSession` refuses while a shot is in flight, while any spread-tagged work is unfinished (`SpreadProcessingWorker.hasPendingSpreads`, new `spread_work` tag), or if the spread index changed during that check. The new session starts at `PdfVolumeAssembler.nextFreeVolumePart(book)`. Spread counters are no longer reset: they stay monotonic, so new spread directories can't collide with failed spreads' leftover raw shots. `resetForNewSession` now `check`s for a shot in flight instead of returning silently.
  - **LAN server `assemble_volume_pdf`** kept every JPEG of the volume in memory (`b"".join`) on every POST. It now streams objects to the `.tmp` file one JPEG at a time and deletes the `.tmp` on failure. 4-component JPEGs map to `/DeviceCMYK`.
* **Checked, no change:**
  - Grok Pass 9 memory: `baseGray` (CV_8UC1) is created inside the `try` after `accumulator`/`weight` are released, and released in the same `finally`. Reusing it as the `max > 245` mask is sound. Ink-recovery (`min ≤ 140`) and paper-highlight (`min > 180`) masks are disjoint, and both copy from `darkest`. The `resolveBurstConflicts` step is ~180MB at 12MP, below the ~348MB alignment peak. `divideByWeight` is still strip-wise. `Mat.get` byte check is correct.
  - `enqueueFinalize` shares `UNIQUE_PIPELINE_NAME` with `APPEND_OR_REPLACE`, so it runs after the spreads queued before it. `setForeground` failure is caught and processing continues. `getForegroundInfo` is supplied.
* **Open (not changed; owners named):**
  - **Grok, math (possible regression in Pass 9):** `fusionChoice` treats `baseGray ≥ 245` as clipped. Clean white paper commonly reads 245–254 before CLAHE. Where the base is bright paper and another shot is misregistered by a stroke width, `fusionChoice(40, 250, base = 250)` now returns `DARKEST` and stamps a ghost glyph. That is the case `BASE` existed to block. Possible tightenings: require a hard clip (`base ≥ 254`), or require base to sit `GLARE_RANGE` above the other shots' paper level nearby. Either needs a synthetic misregistration test.
  - **Gemini / user:** `MainActivity.onStop` enqueues a full-volume rewrite each time the app is backgrounded with staged spreads. Someone who backgrounds between pages brings back O(n²) I/O and a 2× disk spike each time. Suggest merging only on roll, Save & Merge, and new book, or when staged spreads exceed a count.
  - **User decision:** the 60% storage budget leaves no room for the merge after a storage roll (above). The merge is now deferred instead of failing, but on a nearly full device that volume stays as staged spreads. A real fix is an append-only incremental-update volume writer or a smaller storage budget.
  - **Minor:** PDFBox scratch files (`cacheDir/PDFBox*.tmp`) leak if the process dies mid-merge. The server still rebuilds the whole volume synchronously before responding, which is O(volume) per upload and can outlast the client's timeout on large volumes.
* **Modified Files:** `pdf/PdfMerge.kt` (new), `pdf/PdfVolumeAssembler.kt`, `worker/SpreadProcessingWorker.kt`, `core/ScanSessionStore.kt`, `core/BurstCaptureManager.kt`, `ui/MainActivity.kt`, `server/lan_fallback_server.py`, `app/src/test/.../pdf/PdfMergeTest.kt` (new), `HANDOFF.md`.
* **Target for Next Agent:**
  - **Grok:** the `baseGray ≥ 245` ghosting case above, with a test.
  - **Gemini:** the `onStop` finalize policy. A device run of a 100+-spread volume merge to confirm the heap bound.
