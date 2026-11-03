package com.eagertolearn.bookscanner.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitors JVM heap memory and session storage consumption.
 * Emits warnings and threshold breaches when resource consumption passes 60%.
 *
 * Storage usage is measured against the free space recorded when the current volume started
 * ([ScanSessionStore.baselineFreeBytes]), not total device capacity: a phone that is already
 * 60% full must still be able to scan.
 */
class ResourceMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val checkIntervalMs: Long = 3000L,
    private val sessionStore: ScanSessionStore = ScanSessionStore(context)
) {

    data class ResourceSnapshot(
        val heapUsedBytes: Long,
        val heapMaxBytes: Long,
        val heapUsagePercent: Float,
        val storageUsedBytes: Long,
        val storageTotalBytes: Long,
        val storageUsagePercent: Float,
        val heapBreached: Boolean,
        val storageBreached: Boolean,
        val isThresholdBreached: Boolean,
        val breachReason: String? = null
    )

    private val _resourceState = MutableStateFlow(getSnapshot())
    val resourceState: StateFlow<ResourceSnapshot> = _resourceState.asStateFlow()

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                _resourceState.value = getSnapshot()
                delay(checkIntervalMs)
            }
        }
    }

    fun getSnapshot(): ResourceSnapshot {
        // 1. JVM Heap Check
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val heapMax = runtime.maxMemory()
        val heapUsagePct = if (heapMax > 0) (heapUsed.toFloat() / heapMax.toFloat()) * 100f else 0f

        // 2. Storage Check: bytes consumed since the volume's baseline, as a share of that baseline
        val budgetBytes = sessionStore.baselineFreeBytes
        val consumedBytes = (budgetBytes - sessionStore.currentFreeBytes()).coerceAtLeast(0L)
        val storageUsagePct = if (budgetBytes > 0) {
            (consumedBytes.toFloat() / budgetBytes.toFloat()) * 100f
        } else {
            0f
        }

        // 3. Determine 60% Breach Conditions
        val heapBreached = heapUsagePct >= THRESHOLD_PERCENT
        val storageBreached = storageUsagePct >= THRESHOLD_PERCENT
        val isBreached = heapBreached || storageBreached

        val reason = when {
            heapBreached && storageBreached -> "Both Heap (${heapUsagePct.toInt()}%) and Storage (${storageUsagePct.toInt()}%) exceeded 60% limit."
            heapBreached -> "JVM Heap memory reached ${heapUsagePct.toInt()}% (>= 60%)."
            storageBreached -> "Session used ${storageUsagePct.toInt()}% of free storage (>= 60%)."
            else -> null
        }

        return ResourceSnapshot(
            heapUsedBytes = heapUsed,
            heapMaxBytes = heapMax,
            heapUsagePercent = heapUsagePct,
            storageUsedBytes = consumedBytes,
            storageTotalBytes = budgetBytes,
            storageUsagePercent = storageUsagePct,
            heapBreached = heapBreached,
            storageBreached = storageBreached,
            isThresholdBreached = isBreached,
            breachReason = reason
        )
    }

    /**
     * Snapshot for decisions that act on a breach (volume roll / LAN offload). A heap-only breach
     * is re-measured after a GC hint so uncollected garbage does not roll a volume on every page.
     */
    fun getConfirmedSnapshot(): ResourceSnapshot {
        val first = getSnapshot()
        if (!first.heapBreached || first.storageBreached) return first
        System.gc()
        return getSnapshot()
    }

    /**
     * Whether a capture of [estimatedBytes] physically fits while leaving [MIN_FREE_RESERVE_BYTES]
     * free. The 60% rule does not block capture: it is enforced by volume segmentation and LAN
     * offload in the processing pipeline, which re-baselines the budget. Blocking capture at 60%
     * would deadlock when no queued spread is left to trigger that roll.
     */
    fun canAcceptBurst(estimatedBytes: Long): Boolean {
        return sessionStore.currentFreeBytes() - estimatedBytes > MIN_FREE_RESERVE_BYTES
    }

    companion object {
        const val THRESHOLD_PERCENT = 60.0f
        private const val MIN_FREE_RESERVE_BYTES = 256L * 1024L * 1024L
    }
}
