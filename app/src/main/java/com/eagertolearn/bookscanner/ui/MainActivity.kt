package com.eagertolearn.bookscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.eagertolearn.bookscanner.pdf.PdfVolumeAssembler
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
        binding.lanFallbackSwitch.isChecked = sessionStore.lanFallbackEnabled
        binding.lanFallbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            sessionStore.lanFallbackEnabled = isChecked
        }

        binding.captureShotButton.setOnClickListener {
            binding.cameraPreviewView.triggerBurstShot(burstCaptureManager) {
                Toast.makeText(this, "Camera is still initializing...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.forceCompleteButton.setOnClickListener {
            burstCaptureManager.forceCompleteSpread()
        }

        binding.sessionSettingsButton.setOnClickListener {
            showSessionSettingsDialog()
        }
    }

    private fun showSessionSettingsDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val topGap = (12 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }

        val bookLabel = TextView(this).apply {
            setText(com.eagertolearn.bookscanner.R.string.book_name_label)
        }
        val bookInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(sessionStore.bookName)
            setSelection(text.length)
        }

        val urlLabel = TextView(this).apply {
            setText(com.eagertolearn.bookscanner.R.string.lan_fallback_url_label)
            setPadding(0, topGap, 0, 0)
        }
        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(sessionStore.lanServerUrl)
        }

        container.addView(bookLabel)
        container.addView(bookInput)
        container.addView(urlLabel)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Session & Book Settings")
            .setView(container)
            .setPositiveButton("Save & Merge") { _, _ ->
                sessionStore.lanServerUrl = urlInput.text.toString()
                val newBook = ScanSessionStore.sanitizeBookName(bookInput.text.toString())
                if (newBook != sessionStore.bookName) {
                    startNewSession(newBook)
                } else {
                    SpreadProcessingWorker.enqueueFinalize(
                        applicationContext,
                        sessionStore.bookName,
                        sessionStore.volumePart
                    )
                }
                updateVolumeHeader()
            }
            .setNeutralButton("New Book / Reset") { _, _ ->
                sessionStore.lanServerUrl = urlInput.text.toString()
                startNewSession(ScanSessionStore.sanitizeBookName(bookInput.text.toString()))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Closes the current session and starts [newBook]. Refused while a shot is being written or a
     * spread is still queued: a queued spread reads its volume part and committed index from
     * [sessionStore] when it runs, so it would land in the new session's numbering. Partial shots
     * of the current spread are discarded.
     */
    private fun startNewSession(newBook: String) {
        if (burstCaptureManager.isShotInFlight) {
            Toast.makeText(this, "Wait for the current shot to save, then retry.", Toast.LENGTH_SHORT).show()
            return
        }
        val spreadBeforeCheck = sessionStore.activeSpreadIndex
        lifecycleScope.launch {
            val pending = SpreadProcessingWorker.hasPendingSpreads(applicationContext)
            // A shot may have completed a spread while the query was suspended.
            if (pending || burstCaptureManager.isShotInFlight ||
                sessionStore.activeSpreadIndex != spreadBeforeCheck
            ) {
                Toast.makeText(
                    this@MainActivity,
                    "Queued spreads are still processing. Start the new book when they finish.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            SpreadProcessingWorker.enqueueFinalize(
                applicationContext,
                sessionStore.bookName,
                sessionStore.volumePart
            )
            // A name that already has volumes continues after its highest part, so the new
            // session never writes into, or is deduplicated against, an existing volume.
            val startPart = PdfVolumeAssembler(applicationContext).nextFreeVolumePart(newBook)
            sessionStore.resetSession(newBook, startPart)
            burstCaptureManager.resetForNewSession()
            updateVolumeHeader()
            Toast.makeText(
                this@MainActivity,
                String.format(Locale.US, "Started new session: %s_Part%02d.pdf", newBook, startPart),
                Toast.LENGTH_SHORT
            ).show()
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
                binding.forceCompleteButton.isEnabled = state.capturedShots > 0
                binding.captureStatusText.text = if (state.capturedShots == 0) {
                    String.format(
                        Locale.US,
                        "Spread #%d: Ready (0/%d shots)",
                        state.spreadIndex,
                        state.totalShots
                    )
                } else {
                    String.format(
                        Locale.US,
                        "Spread #%d: %d/%d shots captured",
                        state.spreadIndex,
                        state.capturedShots,
                        state.totalShots
                    )
                }
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
                binding.forceCompleteButton.isEnabled = false
                binding.captureStatusText.text = String.format(
                    Locale.US,
                    "Spread #%d queued (%d shots) • Next #%d",
                    state.spreadIndex,
                    state.files.size,
                    sessionStore.activeSpreadIndex
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
            bookName = sessionStore.bookName,
            spreadIndex = spreadIndex,
            shotFiles = shotFiles,
            lanFallbackEnabled = sessionStore.lanFallbackEnabled,
            lanServerUrl = sessionStore.lanServerUrl
        )
    }

    private fun updateVolumeHeader() {
        binding.bookVolumeText.text = String.format(
            Locale.US,
            "Volume: %s_Part%02d.pdf",
            sessionStore.bookName,
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

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            SpreadProcessingWorker.enqueueFinalize(
                context = applicationContext,
                bookName = sessionStore.bookName,
                volumePart = sessionStore.volumePart
            )
        }
    }

    override fun onDestroy() {
        binding.cameraPreviewView.unbindCamera()
        super.onDestroy()
    }
}
