package com.eagertolearn.bookscanner.core

import android.content.Context
import androidx.annotation.MainThread
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Manages 3-4 handheld exposures per book spread.
 * Writes JPEGs directly to app-specific storage, bypassing in-memory Bitmaps. Raw shots live in
 * `noBackupFilesDir/spreads` rather than cacheDir: the OS may purge cacheDir under storage pressure,
 * which is exactly when spreads sit waiting for LAN offload.
 *
 * Threading: all mutable state is confined to the main thread. CameraX encodes and writes the JPEG
 * on its own I/O thread; save callbacks are delivered on the main executor.
 */
class BurstCaptureManager(
    private val context: Context,
    private val resourceMonitor: ResourceMonitor,
    private val sessionStore: ScanSessionStore,
    private val targetShots: Int = 4,
    /** Invoked exactly once per completed spread, on the main thread. */
    private val onSpreadReady: (spreadIndex: Int, files: List<File>) -> Unit
) {

    sealed class CaptureState {
        object Idle : CaptureState()
        data class Capturing(val currentShot: Int, val totalShots: Int) : CaptureState()
        data class SpreadReady(val spreadIndex: Int, val files: List<File>) : CaptureState()
        data class Error(val message: String) : CaptureState()
    }

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var spreadIndex = sessionStore.activeSpreadIndex
    private val capturedFiles = mutableListOf<File>()
    private var shotInFlight = false
    private val estimatedShotSizeBytes = 5L * 1024L * 1024L // ~5MB per full-res JPEG

    init {
        // Resume a spread left partially captured by Activity recreation or process death.
        spreadDir(spreadIndex).listFiles { f -> f.isFile && f.extension == "jpg" && f.length() > 0 }
            ?.sortedBy { it.name }
            ?.let { capturedFiles.addAll(it) }
        if (capturedFiles.size >= targetShots) completeSpread()
    }

    private fun spreadDir(index: Int) = File(File(context.noBackupFilesDir, SPREADS_DIR), "spread_$index")

    /**
     * Executes a single shutter actuation.
     * Rotates internal count and completes spread when targetShots is met.
     */
    @MainThread
    fun captureShot(imageCapture: ImageCapture) {
        if (shotInFlight) return
        if (!resourceMonitor.canAcceptBurst(estimatedShotSizeBytes)) {
            _captureState.value = CaptureState.Error(
                "Capture blocked: device storage is nearly full."
            )
            return
        }

        val nextShotNumber = capturedFiles.size + 1
        _captureState.value = CaptureState.Capturing(nextShotNumber, targetShots)

        val spreadDir = spreadDir(spreadIndex).apply {
            if (!exists()) mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(spreadDir, "shot_${nextShotNumber}_${timestamp}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        shotInFlight = true
        imageCapture.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shotInFlight = false
                    capturedFiles.add(photoFile)
                    if (capturedFiles.size >= targetShots) {
                        completeSpread()
                    } else {
                        _captureState.value = CaptureState.Idle
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    shotInFlight = false
                    photoFile.delete()
                    _captureState.value = CaptureState.Error("Camera capture failed: ${exc.message}")
                }
            }
        )
    }

    /** Completes the spread early. Ignored while a shot is still being written. */
    @MainThread
    fun forceCompleteSpread() {
        if (shotInFlight || capturedFiles.isEmpty()) return
        completeSpread()
    }

    @MainThread
    private fun completeSpread() {
        val completedFiles = capturedFiles.toList()
        val completedIndex = spreadIndex
        // Enqueue before advancing: if the process dies in between, the spread is re-enqueued on
        // restart and the pipeline's committed-spread check drops the duplicate. The reverse order
        // would orphan it.
        onSpreadReady(completedIndex, completedFiles)
        capturedFiles.clear()
        spreadIndex = sessionStore.advanceActiveSpread()

        _captureState.value = CaptureState.SpreadReady(
            spreadIndex = completedIndex,
            files = completedFiles
        )
    }

    suspend fun clearSpreadFiles(files: List<File>) = withContext(Dispatchers.IO) {
        files.forEach { file ->
            if (file.exists()) file.delete()
        }
        files.firstOrNull()?.parentFile?.let { dir ->
            if (dir.exists() && dir.list().isNullOrEmpty()) {
                dir.delete()
            }
        }
    }

    companion object {
        private const val SPREADS_DIR = "spreads"
    }
}
