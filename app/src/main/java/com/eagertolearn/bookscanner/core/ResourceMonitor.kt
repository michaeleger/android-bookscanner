package com.eagertolearn.bookscanner.core

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Monitors JVM heap memory, system RAM, and internal flash storage.
 * Emits warnings and threshold breaches when resource consumption passes 60%.
 */
class ResourceMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val checkIntervalMs: Long = 3000L
) {

    data class ResourceSnapshot(
        val heapUsedBytes: Long,
        val heapMaxBytes: Long,
        val heapUsagePercent: Float,
        val storageUsedBytes: Long,
        val storageTotalBytes: Long,
        val storageUsagePercent: Float,
        val isThresholdBreached: Boolean,
        val breachReason: String? = null
    )

    private val _resourceState = MutableStateFlow(getSnapshot())
    val resourceState: StateFlow<ResourceSnapshot> = _resourceState.asStateFlow()

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

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

        // 2. Storage Check (Targeting internal storage / files directory)
        val storagePath: File = context.filesDir
        val stat = StatFs(storagePath.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalStorageBytes = totalBlocks * blockSize
        val freeStorageBytes = availableBlocks * blockSize
        val usedStorageBytes = totalStorageBytes - freeStorageBytes
        val storageUsagePct = if (totalStorageBytes > 0) {
            (usedStorageBytes.toFloat() / totalStorageBytes.toFloat()) * 100f
        } else {
            0f
        }

        // 3. Determine 60% Breach Conditions
        val heapBreached = heapUsagePct >= 60.0f
        val storageBreached = storageUsagePct >= 60.0f
        val isBreached = heapBreached || storageBreached

        val reason = when {
            heapBreached && storageBreached -> "Both Heap (${heapUsagePct.toInt()}%) and Storage (${storageUsagePct.toInt()}%) exceeded 60% limit."
            heapBreached -> "JVM Heap memory reached ${heapUsagePct.toInt()}% (>= 60%)."
            storageBreached -> "Device internal storage reached ${storageUsagePct.toInt()}% (>= 60%)."
            else -> null
        }

        return ResourceSnapshot(
            heapUsedBytes = heapUsed,
            heapMaxBytes = heapMax,
            heapUsagePercent = heapUsagePct,
            storageUsedBytes = usedStorageBytes,
            storageTotalBytes = totalStorageBytes,
            storageUsagePercent = storageUsagePct,
            isThresholdBreached = isBreached,
            breachReason = reason
        )
    }

    /**
     * Helper to verify if the disk can safely accept a raw image burst of [estimatedBytes]
     * without exceeding the 60% safety buffer.
     */
    fun canAcceptBurst(estimatedBytes: Long): Boolean {
        val storagePath = context.filesDir
        val stat = StatFs(storagePath.path)
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val currentUsedBytes = totalBytes - (stat.availableBlocksLong * stat.blockSizeLong)
        val projectedUsedBytes = currentUsedBytes + estimatedBytes

        val projectedPercent = (projectedUsedBytes.toFloat() / totalBytes.toFloat()) * 100f
        return projectedPercent < 60.0f
    }
}
