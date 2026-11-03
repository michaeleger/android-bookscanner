package com.eagertolearn.bookscanner.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Streams raw cached burst queues over LAN HTTP Multipart POST to the local Linux Python fallback
 * server when the 60% storage/heap threshold is breached.
 *
 * Invariant compliance:
 * - Uses OkHttp streaming [asRequestBody] so raw JPEGs are streamed directly from flash storage
 *   to the socket without loading full files into the JVM heap.
 */
class LanFallbackSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookName = inputData.getString(KEY_BOOK_NAME) ?: "BookScan"
        val volumePart = inputData.getInt(KEY_VOLUME_PART, 1)
        val endpointUrl = inputData.getString(KEY_LAN_SERVER_URL) ?: DEFAULT_LAN_ENDPOINT
        val spreadDirPaths = inputData.getStringArray(KEY_SPREAD_DIRS) ?: emptyArray()

        if (spreadDirPaths.isEmpty()) {
            return@withContext Result.success()
        }

        val jpegMediaType = "image/jpeg".toMediaType()

        for (dirPath in spreadDirPaths) {
            val spreadDir = File(dirPath)
            if (!spreadDir.exists() || !spreadDir.isDirectory) continue

            val shotFiles = spreadDir.listFiles { f ->
                f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg")
            }?.sortedBy { it.name } ?: emptyList()

            if (shotFiles.isEmpty()) {
                spreadDir.delete()
                continue
            }

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("book_name", bookName)
                .addFormDataPart("volume_part", volumePart.toString())
                .addFormDataPart("spread_dir", spreadDir.name)

            for (shotFile in shotFiles) {
                multipartBuilder.addFormDataPart(
                    "shots",
                    shotFile.name,
                    shotFile.asRequestBody(jpegMediaType)
                )
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .post(multipartBuilder.build())
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "LAN fallback server returned HTTP ${response.code} for ${spreadDir.name}")
                        return@withContext Result.retry()
                    }
                }

                // Delete local burst files after confirmed LAN server receipt to relieve storage pressure
                shotFiles.forEach { it.delete() }
                if (spreadDir.list().isNullOrEmpty()) {
                    spreadDir.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "LAN fallback upload failed for ${spreadDir.name}", e)
                return@withContext Result.retry()
            }
        }

        Result.success()
    }

    companion object {
        private const val TAG = "LanFallbackSyncWorker"
        private const val DEFAULT_LAN_ENDPOINT = "http://192.168.1.100:8085/api/v1/ingest-spread"

        const val KEY_BOOK_NAME = "book_name"
        const val KEY_VOLUME_PART = "volume_part"
        const val KEY_SPREAD_DIRS = "spread_dirs"
        const val KEY_LAN_SERVER_URL = "lan_server_url"
    }
}
