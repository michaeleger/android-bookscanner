package com.eagertolearn.bookscanner.core

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages 3-4 handheld exposures per book spread.
 * Writes JPEGs directly to local disk cache, bypassing in-memory Bitmaps.
 */
class BurstCaptureManager(
    private val context: Context,
    private val resourceMonitor: ResourceMonitor,
    private val scope: CoroutineScope,
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
) {

    data class SpreadSession(
        val spreadIndex: Int,
        val targetShots: Int = 4,
        val capturedFiles: MutableList<File> = mutableListOf(),
        val isComplete: Boolean = false
    )

    sealed class CaptureState {
        object Idle : CaptureState()
        data class Capturing(val currentShot: Int, val totalShots: Int) : CaptureState()
        data class SpreadReady(val spreadIndex: Int, val files: List<File>) : CaptureState()
        data class Error(val message: String) : CaptureState()
    }

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private var currentSpread = SpreadSession(spreadIndex = 1, targetShots = 4)
    private val estimatedShotSizeBytes = 5L * 1024L * 1024L // ~5MB per full-res JPEG

    /**
     * Executes a single shutter actuation. 
     * Rotates internal count and completes spread when targetShots is met.
     */
    fun captureShot(imageCapture: ImageCapture) {
        if (!resourceMonitor.canAcceptBurst(estimatedShotSizeBytes)) {
            _captureState.value = CaptureState.Error(
                "Capture blocked: Storage or memory has reached the 60% safety threshold."
            )
            return
        }

        val nextShotNumber = currentSpread.capturedFiles.size + 1
        _captureState.value = CaptureState.Capturing(nextShotNumber, currentSpread.targetShots)

        val spreadDir = File(context.cacheDir, "spread_${currentSpread.spreadIndex}").apply {
            if (!exists()) mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(spreadDir, "shot_${nextShotNumber}_${timestamp}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch(Dispatchers.Default) {
                        currentSpread.capturedFiles.add(photoFile)

                        if (currentSpread.capturedFiles.size >= currentSpread.targetShots) {
                            val completedFiles = currentSpread.capturedFiles.toList()
                            val completedIndex = currentSpread.spreadIndex

                            _captureState.value = CaptureState.SpreadReady(
                                spreadIndex = completedIndex,
                                files = completedFiles
                            )

                            currentSpread = SpreadSession(
                                spreadIndex = completedIndex + 1,
                                targetShots = currentSpread.targetShots
                            )
                        } else {
                            _captureState.value = CaptureState.Idle
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    scope.launch(Dispatchers.Main) {
                        _captureState.value = CaptureState.Error("Camera capture failed: ${exc.message}")
                    }
                }
            }
        )
    }

    fun forceCompleteSpread() {
        if (currentSpread.capturedFiles.isNotEmpty()) {
            val completedFiles = currentSpread.capturedFiles.toList()
            val completedIndex = currentSpread.spreadIndex

            _captureState.value = CaptureState.SpreadReady(
                spreadIndex = completedIndex,
                files = completedFiles
            )

            currentSpread = SpreadSession(
                spreadIndex = completedIndex + 1,
                targetShots = currentSpread.targetShots
            )
        }
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

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
