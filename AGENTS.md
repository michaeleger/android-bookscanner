# Agent Directives & Shared Directory Invariants

This file establishes constraints, standards, and handoff protocols for all CLI models (Gemini, Claude, Grok) operating in this shared workspace.

---

## 1. Project Invariants
* **Target Runtime:** Native Android (Kotlin, Min SDK 28, Target/Compile SDK 34) + Linux Python fallback.
* **No Unbounded Memory Allocation:**
  * Raw camera frames must NEVER reside in heap memory as uncompressed `Bitmap` objects longer than a single frame lifecycle.
  * Intermediate captures must be written to disk cache (`context.cacheDir` or `app-specific storage`) immediately.
  * Every OpenCV native `Mat` must have an explicit `.release()` in a `finally` block or use a `use { ... }` extension.
  * Every Android `Bitmap` must explicitly call `.recycle()` once transferred or saved.
* **Preserve Document Artifacts:**
  * Do NOT use aggressive 1-bit binarization (Otsu/Sauvola). 
  * Use CLAHE on the luminance channel (LAB color space) to retain yellow/pink/green highlighter ink and pencil marginalia.
* **60% Volume Rule:**
  * Segment into `[BookName]_PartXX.pdf` when the session has used 60% of the free space recorded at the start of the current volume, or when JVM heap use exceeds 60% of max heap.
  * A storage breach always starts a new volume and re-baselines free space. A heap-only breach rolls once per episode, because rolling frees no heap, and stays latched until heap recovers.
  * Show a UI warning. Capture continues through a 60% breach. Refuse a burst only when it would leave less than 256MB free.
  * If LAN fallback is enabled, offload that spread's raw shots to the local Linux server and skip the local PDF for that spread. Leave other queued spreads on device.

---

## 2. Model Roles & Handoff Rules

### Gemini (Lead Architect & Scaffolder)
* Primary role: Draft native Android bindings, CameraX setup, Gradle configurations, UI/View layers, and background `WorkManager` tasks.
* Protocol: Write clean Kotlin code adhering strictly to modern AndroidX and Kotlin Coroutines/Flow idioms. Log tasks in `HANDOFF.md`.

### Claude (Auditor & Safety Guard)
* Primary role: Audit diffs for memory leaks, OOM vectors, lifecycle bugs, and unclosed native pointers.
* Protocol: Run static reviews against `git diff`. Focus on matrix memory deallocations, threading safety across Coroutine dispatchers, and coordinate accuracy for PDF text-layer embeddings.

### Grok (Math & Computer Vision Specialist)
* Primary role: Audit and optimize geometric transforms, polynomial baseline curvature calculations, and OpenCV `remap()` matrix math.
* Protocol: Focus on spine-gutter detection formulas, cylindrical projection unwrapping, and edge homography calculations.

---

## 3. Handoff Protocol (`HANDOFF.md`)
Before switching tools, append a short entry to `HANDOFF.md` containing:
1. **Last Updated:** Date / Time / Agent.
2. **Current State:** Working features vs. stubs.
3. **Modified Files:** List of files altered in the last pass.
4. **Target for Next Agent:** Explicit instruction (e.g., "Claude: audit `StitcherEngine.kt` for memory leaks" or "Grok: review polynomial baseline unrolling in `DewarpFilter.kt`").
