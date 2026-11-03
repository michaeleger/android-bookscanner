package com.eagertolearn.bookscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.eagertolearn.bookscanner.core.BurstCaptureManager

/**
 * Hardware-accelerated CameraX preview container with a spine-gutter alignment overlay.
 *
 * Invariant compliance:
 * - Uses [PreviewView.ImplementationMode.PERFORMANCE] (SurfaceView pipeline) so preview
 *   frames never allocate uncompressed [android.graphics.Bitmap] buffers on the JVM heap.
 * - Routes still captures directly to disk cache via [BurstCaptureManager].
 */
class CameraXPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val previewView: PreviewView = PreviewView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    }

    private val overlayView: SpreadGuideOverlayView = SpreadGuideOverlayView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    init {
        addView(previewView)
        addView(overlayView)
    }

    /**
     * Binds CameraX [Preview] and [ImageCapture] use-cases to the given [lifecycleOwner].
     */
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        onError: (String) -> Unit = {}
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val rotation = previewView.display?.rotation ?: Surface.ROTATION_90
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(rotation)
                    .setJpegQuality(95)
                    .build()

                imageCapture = capture

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX use-cases", e)
                onError("Camera initialization failed: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Triggers a single disk-backed burst shot through [BurstCaptureManager].
     */
    fun triggerBurstShot(burstCaptureManager: BurstCaptureManager, onNotReady: () -> Unit = {}) {
        val activeCapture = imageCapture
        if (activeCapture == null) {
            onNotReady()
            return
        }
        burstCaptureManager.captureShot(activeCapture)
    }

    /**
     * Unbinds all CameraX pipelines when the view is torn down.
     */
    fun unbindCamera() {
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    /**
     * Lightweight Canvas overlay rendering book spread bounds and the spine-gutter axis.
     */
    private class SpreadGuideOverlayView(context: Context) : View(context) {

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 100, 181, 246)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val spinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 213, 79)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }

        private val boundsRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val marginX = width * 0.08f
            val marginY = height * 0.08f
            boundsRect.set(marginX, marginY, width - marginX, height - marginY)

            // Outer spread frame
            canvas.drawRoundRect(boundsRect, 12f, 12f, borderPaint)

            // Center vertical spine-gutter alignment guide
            val centerX = width / 2f
            canvas.drawLine(centerX, boundsRect.top, centerX, boundsRect.bottom, spinePaint)
        }
    }

    companion object {
        private const val TAG = "CameraXPreviewView"
    }
}
