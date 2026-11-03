package com.eagertolearn.bookscanner.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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

    override suspend fun doWork(): Result {
        val bookName = inputData.getString(KEY_BOOK_NAME) ?: "BookScan"
        val spreadIndex = inputData.getInt(KEY_SPREAD_INDEX, 1)
        val shotPaths = inputData.getStringArray(KEY_SHOT_PATHS) ?: emptyArray()
        val lanFallbackEnabled = inputData.getBoolean(KEY_LAN_FALLBACK_ENABLED, true)

        // Return success (flagged) rather than failure for unrecoverable spreads: a failed work in
        // an APPEND chain fails every spread queued behind it.
        val shotFiles = shotPaths.map { File(it) }.filter { it.exists() }
        if (shotFiles.isEmpty()) {
            Log.w(TAG, "Spread #$spreadIndex has no shot files on disk; skipping.")
            return Result.success(workDataOf(KEY_OUT_SKIPPED to true))
        }

        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessionStore = ScanSessionStore(applicationContext)
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

            // Apply the 60% Hard Intercept Rule once per spread, before heavy OpenCV processing,
            // so both facing pages land in the same volume.
            val preSnapshot = resourceMonitor.getConfirmedSnapshot()
            val volumePart = sessionStore.resolveVolumePart(
                storageBreached = preSnapshot.storageBreached,
                heapBreached = preSnapshot.heapBreached
            )
            if (preSnapshot.isThresholdBreached && lanFallbackEnabled) {
                // Ship this spread's raw shots to the LAN server instead of processing locally.
                Log.w(TAG, "60% threshold breached prior to spread #$spreadIndex; offloading to LAN (volume $volumePart).")
                val spreadDir = shotFiles.first().parentFile?.absolutePath
                if (spreadDir != null) {
                    enqueueLanFallback(bookName, volumePart, arrayOf(spreadDir))
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

            // 3. Commit both pages to the searchable PDF volume in one atomic save
            val pdfAssembler = PdfVolumeAssembler(applicationContext)
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

    private fun enqueueLanFallback(
        bookName: String,
        volumePart: Int,
        spreadDirs: Array<String>
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
                    LanFallbackSyncWorker.KEY_SPREAD_DIRS to spreadDirs
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
        private const val MAX_ATTEMPTS = 3

        const val KEY_BOOK_NAME = "book_name"
        const val KEY_SPREAD_INDEX = "spread_index"
        const val KEY_SHOT_PATHS = "shot_paths"
        const val KEY_LAN_FALLBACK_ENABLED = "lan_fallback_enabled"

        const val KEY_OUT_PDF_PATH = "out_pdf_path"
        const val KEY_OUT_VOLUME_PART = "out_volume_part"
        const val KEY_OUT_OFFLOADED = "out_offloaded"
        const val KEY_OUT_SKIPPED = "out_skipped"
        const val KEY_OUT_FAILED = "out_failed"

        /** Appends a spread to the single sequential processing chain. */
        fun enqueue(
            context: Context,
            bookName: String,
            spreadIndex: Int,
            shotFiles: List<File>,
            lanFallbackEnabled: Boolean
        ) {
            val workRequest = OneTimeWorkRequestBuilder<SpreadProcessingWorker>()
                .setInputData(
                    workDataOf(
                        KEY_BOOK_NAME to bookName,
                        KEY_SPREAD_INDEX to spreadIndex,
                        KEY_SHOT_PATHS to shotFiles.map { it.absolutePath }.toTypedArray(),
                        KEY_LAN_FALLBACK_ENABLED to lanFallbackEnabled
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
