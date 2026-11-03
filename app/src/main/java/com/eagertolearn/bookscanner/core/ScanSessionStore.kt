package com.eagertolearn.bookscanner.core

import android.content.Context
import android.os.StatFs

/**
 * Persisted single source of truth for scan-session state shared by the UI and background workers
 * (same process, so they share one [android.content.SharedPreferences] instance).
 *
 * - `bookName`: sanitized stem used for `[BookName]_PartXX.pdf` volumes.
 * - `lanServerUrl`: HTTP endpoint for LAN fallback uploads.
 * - `lanFallbackEnabled`: whether 60% breach offloads raw shots to the LAN server.
 * - `volumePart`: active `[BookName]_PartXX.pdf` index. Only [resolveVolumePart] advances it.
 * - `baselineFreeBytes`: free storage recorded when the current volume started. The 60% rule
 *   trips once the session has consumed 60% of this figure.
 * - `activeSpreadIndex`: spread currently being captured. Survives Activity recreation and process
 *   death so a partially captured spread is resumed instead of orphaned, and spread dirs and work
 *   names never collide across restarts.
 * - `lastCommittedSpreadIndex`: highest spread committed to a PDF volume. Spreads are processed
 *   sequentially in index order, so a rerun of an already-committed spread can be detected.
 */
class ScanSessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var bookName: String
        @Synchronized
        get() = prefs.getString(KEY_BOOK_NAME, DEFAULT_BOOK_NAME) ?: DEFAULT_BOOK_NAME
        @Synchronized
        set(value) {
            prefs.edit().putString(KEY_BOOK_NAME, sanitizeBookName(value)).commit()
        }

    var lanServerUrl: String
        @Synchronized
        get() = prefs.getString(KEY_LAN_SERVER_URL, DEFAULT_LAN_ENDPOINT) ?: DEFAULT_LAN_ENDPOINT
        @Synchronized
        set(value) {
            val cleaned = value.trim().ifEmpty { DEFAULT_LAN_ENDPOINT }
            prefs.edit().putString(KEY_LAN_SERVER_URL, cleaned).commit()
        }

    var lanFallbackEnabled: Boolean
        @Synchronized
        get() = prefs.getBoolean(KEY_LAN_FALLBACK_ENABLED, true)
        @Synchronized
        set(value) {
            prefs.edit().putBoolean(KEY_LAN_FALLBACK_ENABLED, value).commit()
        }

    val volumePart: Int
        get() = prefs.getInt(KEY_VOLUME_PART, 1)

    @get:Synchronized
    val baselineFreeBytes: Long
        get() {
            val stored = prefs.getLong(KEY_BASELINE_FREE_BYTES, -1L)
            if (stored > 0L) return stored
            val current = currentFreeBytes()
            prefs.edit().putLong(KEY_BASELINE_FREE_BYTES, current).commit()
            return current
        }

    val activeSpreadIndex: Int
        get() = prefs.getInt(KEY_ACTIVE_SPREAD_INDEX, 1)

    @Synchronized
    fun advanceActiveSpread(): Int {
        val next = activeSpreadIndex + 1
        prefs.edit().putInt(KEY_ACTIVE_SPREAD_INDEX, next).commit()
        return next
    }

    val lastCommittedSpreadIndex: Int
        get() = prefs.getInt(KEY_LAST_COMMITTED_SPREAD_INDEX, 0)

    @Synchronized
    fun markSpreadCommitted(spreadIndex: Int) {
        if (spreadIndex > lastCommittedSpreadIndex) {
            prefs.edit().putInt(KEY_LAST_COMMITTED_SPREAD_INDEX, spreadIndex).commit()
        }
    }

    /**
     * Applies the 60% rule's volume segmentation and returns the volume part to write to.
     *
     * A storage breach always rolls: the roll re-baselines free storage, giving the next volume its
     * own 60% budget of whatever space remains. A heap breach rolls once per episode: rolling frees
     * no heap, so re-rolling while heap stays high would give every spread its own volume.
     */
    @Synchronized
    fun resolveVolumePart(storageBreached: Boolean, heapBreached: Boolean): Int {
        val heapRollLatched = prefs.getBoolean(KEY_HEAP_ROLL_LATCHED, false)
        val shouldRoll = storageBreached || (heapBreached && !heapRollLatched)
        if (!shouldRoll) {
            if (heapRollLatched && !heapBreached) {
                prefs.edit().putBoolean(KEY_HEAP_ROLL_LATCHED, false).commit()
            }
            return volumePart
        }

        val next = volumePart + 1
        prefs.edit()
            .putInt(KEY_VOLUME_PART, next)
            .putLong(KEY_BASELINE_FREE_BYTES, currentFreeBytes())
            .putBoolean(KEY_HEAP_ROLL_LATCHED, heapBreached)
            .commit()
        return next
    }

    /**
     * Starts a fresh book session at [startVolumePart] and re-baselines available storage.
     *
     * Spread counters are not reset. They stay monotonic across sessions, so:
     * - a new `spreads/spread_N` directory never reuses one that still holds raw shots from a
     *   failed spread kept for recovery, which the capture-resume scan would pick up;
     * - [lastCommittedSpreadIndex] never sits above the new session's spreads, which the worker
     *   would skip as already committed and whose raw shots it would delete.
     *
     * Callers must first make sure no spread work is queued (see `SpreadProcessingWorker.hasPendingSpreads`).
     * A queued spread resolves its volume part from this store when it runs.
     */
    @Synchronized
    fun resetSession(newBookName: String, startVolumePart: Int) {
        prefs.edit()
            .putString(KEY_BOOK_NAME, sanitizeBookName(newBookName))
            .putInt(KEY_VOLUME_PART, startVolumePart.coerceAtLeast(1))
            .putLong(KEY_BASELINE_FREE_BYTES, currentFreeBytes())
            .putBoolean(KEY_HEAP_ROLL_LATCHED, false)
            .commit()
    }

    fun currentFreeBytes(): Long {
        val stat = StatFs(appContext.filesDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    companion object {
        const val DEFAULT_BOOK_NAME = "BookScan"
        const val DEFAULT_LAN_ENDPOINT = "http://192.168.1.100:8085/api/v1/ingest-spread"

        private const val PREFS_NAME = "scan_session"
        private const val KEY_BOOK_NAME = "book_name"
        private const val KEY_LAN_SERVER_URL = "lan_server_url"
        private const val KEY_LAN_FALLBACK_ENABLED = "lan_fallback_enabled"
        private const val KEY_VOLUME_PART = "volume_part"
        private const val KEY_BASELINE_FREE_BYTES = "baseline_free_bytes"
        private const val KEY_ACTIVE_SPREAD_INDEX = "active_spread_index"
        private const val KEY_LAST_COMMITTED_SPREAD_INDEX = "last_committed_spread_index"
        private const val KEY_HEAP_ROLL_LATCHED = "heap_roll_latched"

        private val UNSAFE_BOOK_CHARS = Regex("[^A-Za-z0-9._-]")

        fun sanitizeBookName(raw: String): String {
            val cleaned = UNSAFE_BOOK_CHARS.replace(raw.trim(), "_").trim('.', '_')
            return cleaned.take(60).ifEmpty { DEFAULT_BOOK_NAME }
        }
    }
}
