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
