package com.eagertolearn.bookscanner.pdf

import android.content.Context
import android.graphics.BitmapFactory
import com.eagertolearn.bookscanner.vision.useBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
 * - Writes into the `[BookName]_PartXX.pdf` volume chosen by the caller, which applies the 60%
 *   segmentation rule once per spread so facing pages never straddle a volume boundary.
 *
 * Callers must serialize calls: each append rewrites the volume file.
 */
class PdfVolumeAssembler(
    private val context: Context
) {

    data class SpreadAppendResult(
        val pdfFile: File,
        val volumePart: Int,
        /** True when the volume already held this spread (a rerun after commit); nothing was added. */
        val alreadyCommitted: Boolean
    )

    private class OcrPage(val file: File, val width: Int, val height: Int, val text: Text)

    /**
     * Runs on-device ML Kit OCR on each of [dewarpedPageFiles] and appends all of them, with
     * invisible searchable text layers, to `[bookName]_PartXX.pdf` in a single atomic commit: a
     * retry after a failure never leaves half a spread behind.
     *
     * The highest committed spread index is stored in the volume's document info, written in the
     * same save as the pages, so a rerun of an already-committed spread is detected and skipped.
     */
    suspend fun appendSpreadWithOcr(
        bookName: String,
        volumePart: Int,
        spreadIndex: Int,
        dewarpedPageFiles: List<File>
    ): SpreadAppendResult = withContext(Dispatchers.IO) {
        val ocrPages = dewarpedPageFiles.map { pageFile ->
            val (imgWidth, imgHeight) = readImageBoundsOnly(pageFile)
            if (imgWidth <= 0 || imgHeight <= 0) {
                throw IOException("Cannot decode page image bounds: ${pageFile.absolutePath}")
            }
            OcrPage(pageFile, imgWidth, imgHeight, recognizeTextFromFile(pageFile))
        }

        val outputDir = File(context.filesDir, "volumes").apply {
            if (!exists()) mkdirs()
        }
        val volumeFileName = String.format(Locale.US, "%s_Part%02d.pdf", bookName, volumePart)
        val pdfFile = File(outputDir, volumeFileName)
        // PDFBox reads a loaded document lazily from its source file, so saving over that file
        // truncates it mid-read. Write a sibling temp file and rename it into place.
        val tmpFile = File(outputDir, "$volumeFileName.tmp")

        val memSetting = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        val document = if (pdfFile.exists()) {
            PDDocument.load(pdfFile, memSetting)
        } else {
            PDDocument(memSetting)
        }

        try {
            val info = document.documentInformation
            val lastSpread = info.getCustomMetadataValue(META_LAST_SPREAD)?.toIntOrNull() ?: 0
            if (lastSpread >= spreadIndex) {
                return@withContext SpreadAppendResult(pdfFile, volumePart, alreadyCommitted = true)
            }

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

            info.setCustomMetadataValue(META_LAST_SPREAD, spreadIndex.toString())
            document.documentInformation = info
            document.save(tmpFile)
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        } finally {
            document.close()
        }

        if (!tmpFile.renameTo(pdfFile)) {
            tmpFile.delete()
            throw IOException("Failed to commit PDF volume: ${pdfFile.absolutePath}")
        }

        SpreadAppendResult(pdfFile, volumePart, alreadyCommitted = false)
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
        private const val META_LAST_SPREAD = "BookScannerLastSpread"

        // Helvetica AFM metrics in em units: ascender 718, descender -207.
        private const val HELVETICA_DESCENT = 0.207f
        private const val HELVETICA_EM_SPAN = 0.718f + HELVETICA_DESCENT
    }
}
