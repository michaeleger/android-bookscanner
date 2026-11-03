package com.eagertolearn.bookscanner.core

import android.content.Context
import android.os.StatFs

/**
 * Persisted single source of truth for scan-session state shared by the UI and background workers
 * (same process, so they share one [android.content.SharedPreferences] instance).
 *
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

    fun currentFreeBytes(): Long {
        val stat = StatFs(appContext.filesDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    companion object {
        private const val PREFS_NAME = "scan_session"
        private const val KEY_VOLUME_PART = "volume_part"
        private const val KEY_BASELINE_FREE_BYTES = "baseline_free_bytes"
        private const val KEY_ACTIVE_SPREAD_INDEX = "active_spread_index"
        private const val KEY_LAST_COMMITTED_SPREAD_INDEX = "last_committed_spread_index"
        private const val KEY_HEAP_ROLL_LATCHED = "heap_roll_latched"
    }
}
