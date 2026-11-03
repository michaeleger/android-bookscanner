package com.eagertolearn.bookscanner.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.eagertolearn.bookscanner.core.ScanSessionStore
import com.eagertolearn.bookscanner.vision.useBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Searchable PDF volume assembler with ML Kit OCR text-layer embedding and 60% volume segmentation.
 *
 * Invariant compliance:
 * - Reads image dimensions with `inJustDecodeBounds = true`; the single full decode needed for OCR
 *   is recycled as soon as recognition completes.
 * - Streams JPEG bytes from disk into PDFBox via [PDImageXObject.createFromFileByContent] using
 *   temp-file backed [MemoryUsageSetting.setupTempFileOnly].
 * - Writes each spread as an isolated 2-page PDF (`spread_XXXX.pdf`, O(1) disk I/O per spread) and
 *   merges staged spreads into `[BookName]_PartXX.pdf` on volume roll or session finalization via
 *   [finalizeVolume]. Each merge rewrites the whole volume, so cost is O(volume) per finalize, not
 *   per spread. [PdfMerge] keeps one source open at a time to bound heap.
 *
 * Callers must serialize calls through the sequential spread processing pipeline.
 */
class PdfVolumeAssembler(
    private val context: Context
) {

    data class SpreadAppendResult(
        val pdfFile: File,
        val volumePart: Int,
        /** True when the volume or staging dir already held this spread; nothing was added. */
        val alreadyCommitted: Boolean
    )

    private class OcrPage(val file: File, val width: Int, val height: Int, val text: Text)

    private val volumesDir: File
        get() = File(context.filesDir, "volumes").apply {
            if (!exists()) mkdirs()
        }

    private fun volumeFile(bookName: String, volumePart: Int): File {
        val fileName = String.format(Locale.US, "%s_Part%02d.pdf", bookName, volumePart)
        return File(volumesDir, fileName)
    }

    private fun stagingDir(bookName: String, volumePart: Int): File {
        val dirName = String.format(Locale.US, "%s_Part%02d_spreads", bookName, volumePart)
        return File(volumesDir, dirName)
    }

    private fun stagedSpreadFile(stagingDir: File, spreadIndex: Int): File {
        val fileName = String.format(Locale.US, "spread_%04d.pdf", spreadIndex)
        return File(stagingDir, fileName)
    }

    /**
     * Runs on-device ML Kit OCR on each of [dewarpedPageFiles] and writes a self-contained 2-page
     * searchable PDF (`spread_XXXX.pdf`) into the volume's staging directory in a single atomic
     * save. Staged spreads are merged into `[bookName]_PartXX.pdf` on volume roll or session close
     * via [finalizeVolume].
     */
    suspend fun appendSpreadWithOcr(
        bookName: String,
        volumePart: Int,
        spreadIndex: Int,
        dewarpedPageFiles: List<File>
    ): SpreadAppendResult = withContext(Dispatchers.IO) {
        val stageDir = stagingDir(bookName, volumePart).apply {
            if (!exists()) mkdirs()
        }
        val spreadPdfFile = stagedSpreadFile(stageDir, spreadIndex)
        if (spreadPdfFile.exists() && spreadPdfFile.length() > 0L) {
            return@withContext SpreadAppendResult(spreadPdfFile, volumePart, alreadyCommitted = true)
        }

        val mergedVolumeFile = volumeFile(bookName, volumePart)
        if (mergedVolumeFile.exists() && readLastSpreadIndex(mergedVolumeFile) >= spreadIndex) {
            return@withContext SpreadAppendResult(mergedVolumeFile, volumePart, alreadyCommitted = true)
        }

        val ocrPages = dewarpedPageFiles.map { pageFile ->
            val (imgWidth, imgHeight) = readImageBoundsOnly(pageFile)
            if (imgWidth <= 0 || imgHeight <= 0) {
                throw IOException("Cannot decode page image bounds: ${pageFile.absolutePath}")
            }
            OcrPage(pageFile, imgWidth, imgHeight, recognizeTextFromFile(pageFile))
        }

        val tmpFile = File(stageDir, "${spreadPdfFile.name}.tmp")
        val memSetting = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        val document = PDDocument(memSetting)

        try {
            for (ocrPage in ocrPages) {
                val pageWidthPt = PDRectangle.LETTER.width
                val pageHeightPt = pageWidthPt * (ocrPage.height.toFloat() / ocrPage.width.toFloat())

                val pageRect = PDRectangle(pageWidthPt, pageHeightPt)
                val page = PDPage(pageRect)
                document.addPage(page)

                val pdImage = PDImageXObject.createFromFileByContent(ocrPage.file, document)
                PDPageContentStream(document, page).use { contentStream ->
                    // 1. Draw the dewarped LAB-CLAHE visual page image
                    contentStream.drawImage(pdImage, 0f, 0f, pageWidthPt, pageHeightPt)

                    // 2. Embed invisible searchable OCR text layer mapped from pixel to PDF coordinates
                    embedInvisibleOcrLayer(
                        contentStream = contentStream,
                        ocrText = ocrPage.text,
                        imgWidth = ocrPage.width,
                        imgHeight = ocrPage.height,
                        pageWidthPt = pageWidthPt,
                        pageHeightPt = pageHeightPt
                    )
                }
            }

            val info = document.documentInformation
            info.setCustomMetadataValue(META_LAST_SPREAD, spreadIndex.toString())
            document.documentInformation = info
            document.save(tmpFile)
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        } finally {
            document.close()
        }

        if (!tmpFile.renameTo(spreadPdfFile)) {
            tmpFile.delete()
            throw IOException("Failed to commit staged spread PDF: ${spreadPdfFile.absolutePath}")
        }

        SpreadAppendResult(spreadPdfFile, volumePart, alreadyCommitted = false)
    }

    /**
     * Merges all staged `spread_XXXX.pdf` files for `(bookName, volumePart)` into
     * `[bookName]_PartXX.pdf` in a single O(n) pass and deletes the staged spread files once the
     * merged volume is atomically renamed into place.
     */
    suspend fun finalizeVolume(bookName: String, volumePart: Int): File? = withContext(Dispatchers.IO) {
        val stageDir = stagingDir(bookName, volumePart)
        val targetVolumeFile = volumeFile(bookName, volumePart)
        if (!stageDir.exists()) {
            return@withContext targetVolumeFile.takeIf { it.exists() }
        }

        // Remove any orphaned .tmp files from interrupted spread saves
        stageDir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach { it.delete() }

        val stagedSpreads = stageDir.listFiles { f ->
            f.isFile && f.extension == "pdf" && f.length() > 0L && parseSpreadIndex(f.name) > 0
        }?.sortedBy { parseSpreadIndex(it.name) } ?: emptyList()

        if (stagedSpreads.isEmpty()) {
            if (stageDir.list().isNullOrEmpty()) stageDir.delete()
            return@withContext targetVolumeFile.takeIf { it.exists() }
        }

        val existingLastSpread = if (targetVolumeFile.exists()) {
            readLastSpreadIndex(targetVolumeFile)
        } else {
            0
        }
        val unmergedSpreads = stagedSpreads.filter { parseSpreadIndex(it.name) > existingLastSpread }
        if (unmergedSpreads.isEmpty()) {
            stagedSpreads.forEach { it.delete() }
            if (stageDir.list().isNullOrEmpty()) stageDir.delete()
            return@withContext targetVolumeFile
        }

        val maxSpreadIndex = unmergedSpreads.maxOf { parseSpreadIndex(it.name) }
            .coerceAtLeast(existingLastSpread)
        val tmpVolumeFile = File(volumesDir, "${targetVolumeFile.name}.tmp")
        tmpVolumeFile.delete() // left behind if the process died mid-merge

        val sources = buildList {
            if (targetVolumeFile.exists()) add(targetVolumeFile)
            addAll(unmergedSpreads)
        }

        // The merge needs about twice the merged size free (destination scratch + output) while the
        // sources still exist. A volume that rolled on a storage breach often leaves less than that.
        // Running out mid-write fails the whole merge, so defer instead. The staged spreads stay put
        // and finalizeAllPendingVolumes retries on the next session close.
        val requiredBytes = PdfMerge.requiredFreeBytes(sources)
        val freeBytes = ScanSessionStore(context).currentFreeBytes()
        if (freeBytes < requiredBytes) {
            Log.w(
                TAG,
                "Deferring merge of ${targetVolumeFile.name}: needs ${requiredBytes shr 20}MB free, " +
                    "has ${freeBytes shr 20}MB. ${unmergedSpreads.size} spreads stay staged."
            )
            return@withContext targetVolumeFile.takeIf { it.exists() }
        }

        try {
            PdfMerge.mergeSequentially(
                sources = sources,
                destination = tmpVolumeFile,
                tempDir = context.cacheDir,
                info = PDDocumentInformation().apply {
                    setCustomMetadataValue(META_LAST_SPREAD, maxSpreadIndex.toString())
                }
            )
        } catch (e: Exception) {
            tmpVolumeFile.delete()
            throw e
        }

        if (!tmpVolumeFile.renameTo(targetVolumeFile)) {
            tmpVolumeFile.delete()
            throw IOException("Failed to commit merged PDF volume: ${targetVolumeFile.absolutePath}")
        }

        stagedSpreads.forEach { it.delete() }
        if (stageDir.list().isNullOrEmpty()) {
            stageDir.delete()
        }
        targetVolumeFile
    }

    /**
     * Finalizes any `_PartXX_spreads` staging directories present in [volumesDir].
     */
    suspend fun finalizeAllPendingVolumes(): List<File> = withContext(Dispatchers.IO) {
        val finalized = mutableListOf<File>()
        val dirs = volumesDir.listFiles { f -> f.isDirectory && f.name.endsWith("_spreads") }
            ?.sortedBy { it.name }
            ?: emptyList()
        for (dir in dirs) {
            val match = STAGING_DIR_REGEX.matchEntire(dir.name) ?: continue
            val book = match.groupValues[1]
            val part = match.groupValues[2].toIntOrNull() ?: continue
            finalizeVolume(book, part)?.let { finalized.add(it) }
        }
        finalized
    }

    /**
     * First volume part for [bookName] with no merged volume and no staging directory on disk.
     * A new session under a name that already has volumes must start here: an existing
     * `_PartXX.pdf` with a higher last-spread marker would otherwise report the new session's
     * spreads as already committed, and the worker would delete their raw shots.
     */
    fun nextFreeVolumePart(bookName: String): Int {
        val prefix = "${bookName}_Part"
        val usedParts = volumesDir.list()?.mapNotNull { name ->
            if (!name.startsWith(prefix)) return@mapNotNull null
            VOLUME_PART_SUFFIX_REGEX.matchEntire(name.substring(prefix.length))
                ?.groupValues?.get(1)?.toIntOrNull()
        } ?: emptyList()
        return (usedParts.maxOrNull() ?: 0) + 1
    }

    private fun readLastSpreadIndex(pdfFile: File): Int {
        if (!pdfFile.exists() || pdfFile.length() <= 0L) return 0
        val memSetting = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        return try {
            PDDocument.load(pdfFile, memSetting).use { doc ->
                doc.documentInformation.getCustomMetadataValue(META_LAST_SPREAD)?.toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun parseSpreadIndex(fileName: String): Int {
        return SPREAD_FILE_REGEX.matchEntire(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private suspend fun recognizeTextFromFile(imageFile: File): Text {
        // Decode once and recycle explicitly; InputImage.fromFilePath would leave the full-res
        // Bitmap to the GC. The page JPEG is written by OpenCV without EXIF, so rotation is 0.
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw IOException("Cannot decode page image: ${imageFile.absolutePath}")
        return bitmap.useBitmap { bmp ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            } finally {
                recognizer.close()
            }
        }
    }

    private fun readImageBoundsOnly(imageFile: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        return Pair(options.outWidth, options.outHeight)
    }

    /**
     * Maps ML Kit line bounding boxes (top-left pixel origin) into PDF user space (bottom-left
     * point origin). Font size is chosen so Helvetica's ascent-to-descent span fills the box
     * height, the baseline sits one descent above the box bottom, and the text matrix stretches
     * the string horizontally to the box width so search hits and selections cover the glyphs.
     */
    private fun embedInvisibleOcrLayer(
        contentStream: PDPageContentStream,
        ocrText: Text,
        imgWidth: Int,
        imgHeight: Int,
        pageWidthPt: Float,
        pageHeightPt: Float
    ) {
        val scaleX = pageWidthPt / imgWidth.toFloat()
        val scaleY = pageHeightPt / imgHeight.toFloat()
        val font = PDType1Font.HELVETICA

        contentStream.beginText()
        contentStream.setRenderingMode(RenderingMode.NEITHER)

        for (block in ocrText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val sanitized = sanitizePdfAscii(line.text)
                if (sanitized.isBlank()) continue

                val boxWidthPt = box.width() * scaleX
                val boxHeightPt = box.height() * scaleY
                if (boxWidthPt <= 0f || boxHeightPt <= 0f) continue

                val fontSizePt = (boxHeightPt / HELVETICA_EM_SPAN).coerceIn(1f, 144f)
                val textWidthPt = font.getStringWidth(sanitized) / 1000f * fontSizePt
                if (textWidthPt <= 0f) continue

                val pdfX = box.left * scaleX
                val boxBottomPt = pageHeightPt - (box.bottom * scaleY)
                val baselineY = boxBottomPt + HELVETICA_DESCENT * fontSizePt
                val stretchX = boxWidthPt / textWidthPt

                contentStream.setFont(font, fontSizePt)
                contentStream.setTextMatrix(Matrix(stretchX, 0f, 0f, 1f, pdfX, baselineY))
                contentStream.showText(sanitized)
            }
        }

        contentStream.endText()
    }

    private fun sanitizePdfAscii(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.code in 32..126) {
                sb.append(ch)
            } else {
                sb.append(' ')
            }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "PdfVolumeAssembler"
        private const val META_LAST_SPREAD = "BookScannerLastSpread"
        private val SPREAD_FILE_REGEX = Regex("""spread_(\d+)\.pdf""")
        private val STAGING_DIR_REGEX = Regex("""(.+)_Part(\d+)_spreads""")
        private val VOLUME_PART_SUFFIX_REGEX = Regex("""(\d+)(?:\.pdf|\.pdf\.tmp|_spreads)""")

        // Helvetica AFM metrics in em units: ascender 718, descender -207.
        private const val HELVETICA_DESCENT = 0.207f
        private const val HELVETICA_EM_SPAN = 0.718f + HELVETICA_DESCENT
    }
}
