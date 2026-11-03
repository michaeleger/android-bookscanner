package com.eagertolearn.bookscanner.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eagertolearn.bookscanner.core.NativeLibs
import com.eagertolearn.bookscanner.core.ResourceMonitor
import com.eagertolearn.bookscanner.core.ScanSessionStore
import com.eagertolearn.bookscanner.pdf.PdfVolumeAssembler
import com.eagertolearn.bookscanner.vision.DewarpFilter
import com.eagertolearn.bookscanner.vision.StitcherEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background [CoroutineWorker] orchestrating the disk-backed spread processing pipeline:
 * 1. Multi-shot spread stitching & spine-gutter splitting ([StitcherEngine]).
 * 2. Polynomial baseline dewarping & LAB L-channel CLAHE ([DewarpFilter]).
 * 3. Searchable PDF assembly & 60% volume segmentation ([PdfVolumeAssembler]).
 * 4. Immediate disk cache cleanup or LAN fallback handoff ([LanFallbackSyncWorker]).
 *
 * Spreads must be enqueued with [enqueue], which chains them under one unique work name so they
 * run one at a time in capture order: concurrent runs would race on the same PDF volume file and
 * stack several full-resolution OpenCV pipelines in native memory.
 */
class SpreadProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val spreadIndex = inputData.getInt(KEY_SPREAD_INDEX, 1)
        return createForegroundInfo(spreadIndex)
    }

    override suspend fun doWork(): Result {
        val sessionStore = ScanSessionStore(applicationContext)
        val bookName = inputData.getString(KEY_BOOK_NAME) ?: sessionStore.bookName
        val finalizeOnly = inputData.getBoolean(KEY_FINALIZE_ONLY, false)
        if (finalizeOnly) {
            return try {
                NativeLibs.ensureInitialized(applicationContext)
                val targetPart = inputData.getInt(KEY_VOLUME_PART, sessionStore.volumePart)
                val assembler = PdfVolumeAssembler(applicationContext)
                val merged = assembler.finalizeVolume(bookName, targetPart)
                assembler.finalizeAllPendingVolumes()
                Result.success(
                    workDataOf(
                        KEY_OUT_PDF_PATH to (merged?.absolutePath ?: ""),
                        KEY_OUT_VOLUME_PART to targetPart,
                        KEY_OUT_OFFLOADED to false
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize volume for $bookName", e)
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                    Result.success(workDataOf(KEY_OUT_FAILED to true))
                } else {
                    Result.retry()
                }
            }
        }

        val spreadIndex = inputData.getInt(KEY_SPREAD_INDEX, 1)
        val shotPaths = inputData.getStringArray(KEY_SHOT_PATHS) ?: emptyArray()
        val lanFallbackEnabled = inputData.getBoolean(KEY_LAN_FALLBACK_ENABLED, sessionStore.lanFallbackEnabled)
        val lanServerUrl = inputData.getString(KEY_LAN_SERVER_URL) ?: sessionStore.lanServerUrl

        // Return success (flagged) rather than failure for unrecoverable spreads: a failed work in
        // an APPEND chain fails every spread queued behind it.
        val shotFiles = shotPaths.map { File(it) }.filter { it.exists() }
        if (shotFiles.isEmpty()) {
            Log.w(TAG, "Spread #$spreadIndex has no shot files on disk; skipping.")
            return Result.success(workDataOf(KEY_OUT_SKIPPED to true))
        }

        try {
            setForeground(createForegroundInfo(spreadIndex))
        } catch (e: Exception) {
            Log.w(TAG, "Foreground promotion skipped for spread #$spreadIndex", e)
        }

        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resourceMonitor = ResourceMonitor(applicationContext, workerScope, sessionStore = sessionStore)

        val workDir = File(applicationContext.cacheDir, "work_spread_$spreadIndex")
        val intermediateFiles = mutableListOf<File>()

        try {
            if (spreadIndex <= sessionStore.lastCommittedSpreadIndex) {
                // Rerun of a spread already in a volume (process died after commit); just clean up.
                deleteRawShots(shotFiles)
                return Result.success(workDataOf(KEY_OUT_SKIPPED to true))
            }

            NativeLibs.ensureInitialized(applicationContext)
            val pdfAssembler = PdfVolumeAssembler(applicationContext)

            // Apply the 60% Hard Intercept Rule once per spread, before heavy OpenCV processing,
            // so both facing pages land in the same volume.
            val previousVolumePart = sessionStore.volumePart
            val preSnapshot = resourceMonitor.getConfirmedSnapshot()
            val volumePart = sessionStore.resolveVolumePart(
                storageBreached = preSnapshot.storageBreached,
                heapBreached = preSnapshot.heapBreached
            )
            if (volumePart > previousVolumePart) {
                // Volume rolled: merge all staged per-spread PDFs of the previous volume part now.
                // The roll is already persisted, so a retry of this spread would not come back here.
                // A failed merge must not cost this spread its attempts. The staged spreads stay on
                // disk and finalizeAllPendingVolumes picks them up at the next session close.
                try {
                    pdfAssembler.finalizeVolume(bookName, previousVolumePart)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Merge of volume $previousVolumePart failed; left staged", e)
                }
            }
            if (preSnapshot.isThresholdBreached && lanFallbackEnabled) {
                // Ship this spread's raw shots to the LAN server instead of processing locally.
                Log.w(TAG, "60% threshold breached prior to spread #$spreadIndex; offloading to LAN (volume $volumePart).")
                val spreadDir = shotFiles.first().parentFile?.absolutePath
                if (spreadDir != null) {
                    enqueueLanFallback(bookName, volumePart, arrayOf(spreadDir), lanServerUrl)
                }
                return Result.success(
                    workDataOf(
                        KEY_OUT_VOLUME_PART to volumePart,
                        KEY_OUT_OFFLOADED to true
                    )
                )
            }

            // 1. Stitch & split spread into left and right raw pages on disk
            val stitcher = StitcherEngine()
            val splitResult = stitcher.stitchAndSplitSpread(
                spreadIndex = spreadIndex,
                shotFiles = shotFiles,
                outputDir = workDir
            )
            intermediateFiles.add(splitResult.leftPageFile)
            intermediateFiles.add(splitResult.rightPageFile)

            // 2. Dewarp & apply LAB L-channel CLAHE to preserve highlighters and marginalia
            val dewarpFilter = DewarpFilter()
            val leftDewarpedFile = File(workDir, "spread_${spreadIndex}_left_dewarped.jpg")
            val rightDewarpedFile = File(workDir, "spread_${spreadIndex}_right_dewarped.jpg")
            intermediateFiles.add(leftDewarpedFile)
            intermediateFiles.add(rightDewarpedFile)

            dewarpFilter.processPage(
                rawPageFile = splitResult.leftPageFile,
                outputFile = leftDewarpedFile,
                pageSide = DewarpFilter.PageSide.LEFT
            )
            dewarpFilter.processPage(
                rawPageFile = splitResult.rightPageFile,
                outputFile = rightDewarpedFile,
                pageSide = DewarpFilter.PageSide.RIGHT
            )

            // 3. Stage both pages as a 2-page searchable PDF in one atomic save
            val pdfRes = pdfAssembler.appendSpreadWithOcr(
                bookName = bookName,
                volumePart = volumePart,
                spreadIndex = spreadIndex,
                dewarpedPageFiles = listOf(leftDewarpedFile, rightDewarpedFile)
            )
            sessionStore.markSpreadCommitted(spreadIndex)

            // 4. Clean up raw burst shots now that pages are committed to PDF
            deleteRawShots(shotFiles)

            return Result.success(
                workDataOf(
                    KEY_OUT_PDF_PATH to pdfRes.pdfFile.absolutePath,
                    KEY_OUT_VOLUME_PART to pdfRes.volumePart,
                    KEY_OUT_OFFLOADED to false
                )
            )
        } catch (e: CancellationException) {
            // WorkManager stopped us (quota, Doze, constraints); let it reschedule without
            // counting this as a processing failure.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process spread #$spreadIndex (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                // Raw shots stay in noBackupFilesDir/spreads/spread_N for manual recovery or LAN re-upload.
                return Result.success(workDataOf(KEY_OUT_FAILED to true))
            }
            return Result.retry()
        } finally {
            intermediateFiles.forEach { file ->
                if (file.exists()) file.delete()
            }
            if (workDir.exists() && workDir.list().isNullOrEmpty()) {
                workDir.delete()
            }
            workerScope.cancel()
        }
    }

    private fun deleteRawShots(shotFiles: List<File>) {
        shotFiles.forEach { file ->
            if (file.exists()) file.delete()
        }
        shotFiles.firstOrNull()?.parentFile?.let { parent ->
            if (parent.exists() && parent.list().isNullOrEmpty()) {
                parent.delete()
            }
        }
    }

    private fun createForegroundInfo(spreadIndex: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Book Spread Processing",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("BookScanner")
            .setContentText("Processing spread #$spreadIndex...")
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun enqueueLanFallback(
        bookName: String,
        volumePart: Int,
        spreadDirs: Array<String>,
        lanServerUrl: String
    ) {
        val syncRequest = OneTimeWorkRequestBuilder<LanFallbackSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    LanFallbackSyncWorker.KEY_BOOK_NAME to bookName,
                    LanFallbackSyncWorker.KEY_VOLUME_PART to volumePart,
                    LanFallbackSyncWorker.KEY_SPREAD_DIRS to spreadDirs,
                    LanFallbackSyncWorker.KEY_LAN_SERVER_URL to lanServerUrl
                )
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "lan_fallback_sync_part_$volumePart",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequest
        )
    }

    companion object {
        private const val TAG = "SpreadProcessingWorker"
        private const val UNIQUE_PIPELINE_NAME = "spread_processing_pipeline"
        private const val NOTIFICATION_CHANNEL_ID = "book_scanner_processing"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ATTEMPTS = 3
        private const val TAG_SPREAD_WORK = "spread_work"

        const val KEY_BOOK_NAME = "book_name"
        const val KEY_SPREAD_INDEX = "spread_index"
        const val KEY_SHOT_PATHS = "shot_paths"
        const val KEY_LAN_FALLBACK_ENABLED = "lan_fallback_enabled"
        const val KEY_LAN_SERVER_URL = "lan_server_url"
        const val KEY_FINALIZE_ONLY = "finalize_only"
        const val KEY_VOLUME_PART = "volume_part"

        const val KEY_OUT_PDF_PATH = "out_pdf_path"
        const val KEY_OUT_VOLUME_PART = "out_volume_part"
        const val KEY_OUT_OFFLOADED = "out_offloaded"
        const val KEY_OUT_SKIPPED = "out_skipped"
        const val KEY_OUT_FAILED = "out_failed"

        /**
         * True while any spread (not finalize-only) work in the pipeline is unfinished. A session
         * reset has to wait for these: a queued spread reads its volume part and committed index
         * from [ScanSessionStore] when it runs, so after a reset it would land in the new
         * session's volume numbering under the old book name.
         */
        suspend fun hasPendingSpreads(context: Context): Boolean {
            val infos = WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_PIPELINE_NAME)
                .first()
            return infos.any { !it.state.isFinished && TAG_SPREAD_WORK in it.tags }
        }

        /** Appends a spread to the single sequential processing chain. */
        fun enqueue(
            context: Context,
            bookName: String,
            spreadIndex: Int,
            shotFiles: List<File>,
            lanFallbackEnabled: Boolean,
            lanServerUrl: String = ScanSessionStore(context).lanServerUrl
        ) {
            val workRequest = OneTimeWorkRequestBuilder<SpreadProcessingWorker>()
                .addTag(TAG_SPREAD_WORK)
                .setInputData(
                    workDataOf(
                        KEY_BOOK_NAME to bookName,
                        KEY_SPREAD_INDEX to spreadIndex,
                        KEY_SHOT_PATHS to shotFiles.map { it.absolutePath }.toTypedArray(),
                        KEY_LAN_FALLBACK_ENABLED to lanFallbackEnabled,
                        KEY_LAN_SERVER_URL to lanServerUrl
                    )
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_PIPELINE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            )
        }

        /**
         * Appends a volume-merge step to the sequential processing chain so all staged per-spread
         * PDFs for `(bookName, volumePart)` are merged into `[bookName]_PartXX.pdf` once preceding
         * spreads finish.
         */
        fun enqueueFinalize(
            context: Context,
            bookName: String,
            volumePart: Int
        ) {
            val workRequest = OneTimeWorkRequestBuilder<SpreadProcessingWorker>()
                .setInputData(
                    workDataOf(
                        KEY_BOOK_NAME to bookName,
                        KEY_VOLUME_PART to volumePart,
                        KEY_FINALIZE_ONLY to true
                    )
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_PIPELINE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            )
        }
    }
}
