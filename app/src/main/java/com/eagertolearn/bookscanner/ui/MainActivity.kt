package com.eagertolearn.bookscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eagertolearn.bookscanner.core.BurstCaptureManager
import com.eagertolearn.bookscanner.core.NativeLibs
import com.eagertolearn.bookscanner.core.ResourceMonitor
import com.eagertolearn.bookscanner.core.ScanSessionStore
import com.eagertolearn.bookscanner.databinding.ActivityMainBinding
import com.eagertolearn.bookscanner.worker.SpreadProcessingWorker
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Primary capture and telemetry coordinator Activity.
 *
 * Responsibilities:
 * - Binds [CameraXPreviewView] and manages runtime camera permissions.
 * - Observes [ResourceMonitor] to surface the 60% Hard Intercept Rule: displays the UI warning
 *   banner when Heap or Storage >= 60%. Volume segmentation and LAN offload are decided by
 *   [SpreadProcessingWorker] against [ScanSessionStore], so they also happen with the UI closed.
 * - Schedules background [SpreadProcessingWorker] jobs for spreads completed by [BurstCaptureManager].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStore: ScanSessionStore
    private lateinit var resourceMonitor: ResourceMonitor
    private lateinit var burstCaptureManager: BurstCaptureManager

    private var bookName: String = "BookScan"

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraPreview()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required to scan book spreads.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize native OpenCV & PDFBox Android assets
        NativeLibs.ensureInitialized(applicationContext)

        sessionStore = ScanSessionStore(applicationContext)
        resourceMonitor = ResourceMonitor(
            context = applicationContext,
            scope = lifecycleScope,
            sessionStore = sessionStore
        )
        burstCaptureManager = BurstCaptureManager(
            context = applicationContext,
            resourceMonitor = resourceMonitor,
            sessionStore = sessionStore,
            onSpreadReady = ::enqueueSpreadProcessing
        )

        setupUiControls()
        observeStateFlows()
        ensureCameraPermissionAndBind()
    }

    private fun setupUiControls() {
        updateVolumeHeader()

        binding.captureShotButton.setOnClickListener {
            binding.cameraPreviewView.triggerBurstShot(burstCaptureManager) {
                Toast.makeText(this, "Camera is still initializing...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.forceCompleteButton.setOnClickListener {
            burstCaptureManager.forceCompleteSpread()
        }
    }

    private fun observeStateFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    resourceMonitor.resourceState.collect { snapshot ->
                        renderResourceSnapshot(snapshot)
                    }
                }
                launch {
                    burstCaptureManager.captureState.collect { state ->
                        renderCaptureState(state)
                    }
                }
            }
        }
    }

    private fun renderResourceSnapshot(snapshot: ResourceMonitor.ResourceSnapshot) {
        val heapPct = snapshot.heapUsagePercent.toInt()
        val storagePct = snapshot.storageUsagePercent.toInt()
        updateVolumeHeader()
        binding.resourceTelemetryText.text = String.format(
            Locale.US,
            "Heap: %d%% | Storage: %d%%",
            heapPct,
            storagePct
        )

        if (snapshot.isThresholdBreached) {
            binding.resourceTelemetryText.setTextColor(Color.parseColor("#FF8A65"))
            binding.warningBannerText.visibility = View.VISIBLE
            binding.warningBannerText.text = snapshot.breachReason
                ?: getString(com.eagertolearn.bookscanner.R.string.resource_warning_banner)
        } else {
            binding.resourceTelemetryText.setTextColor(Color.parseColor("#81C784"))
            binding.warningBannerText.visibility = View.GONE
        }
    }

    private fun renderCaptureState(state: BurstCaptureManager.CaptureState) {
        when (state) {
            is BurstCaptureManager.CaptureState.Idle -> {
                binding.captureShotButton.isEnabled = true
                binding.forceCompleteButton.isEnabled = true
            }

            is BurstCaptureManager.CaptureState.Capturing -> {
                binding.captureShotButton.isEnabled = false
                binding.forceCompleteButton.isEnabled = false
                binding.captureStatusText.text = String.format(
                    Locale.US,
                    "Capturing shot %d/%d...",
                    state.currentShot,
                    state.totalShots
                )
            }

            is BurstCaptureManager.CaptureState.SpreadReady -> {
                binding.captureShotButton.isEnabled = true
                binding.forceCompleteButton.isEnabled = true
                binding.captureStatusText.text = String.format(
                    Locale.US,
                    "Spread #%d queued (%d shots)",
                    state.spreadIndex,
                    state.files.size
                )
            }

            is BurstCaptureManager.CaptureState.Error -> {
                binding.captureShotButton.isEnabled = true
                binding.forceCompleteButton.isEnabled = true
                binding.captureStatusText.text = state.message
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun enqueueSpreadProcessing(spreadIndex: Int, shotFiles: List<File>) {
        SpreadProcessingWorker.enqueue(
            context = applicationContext,
            bookName = bookName,
            spreadIndex = spreadIndex,
            shotFiles = shotFiles,
            lanFallbackEnabled = binding.lanFallbackSwitch.isChecked
        )
    }

    private fun updateVolumeHeader() {
        binding.bookVolumeText.text = String.format(
            Locale.US,
            "Volume: %s_Part%02d.pdf",
            bookName,
            sessionStore.volumePart
        )
    }

    private fun ensureCameraPermissionAndBind() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        binding.cameraPreviewView.bindToLifecycle(this) { errorMsg ->
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        binding.cameraPreviewView.unbindCamera()
        super.onDestroy()
    }
}
