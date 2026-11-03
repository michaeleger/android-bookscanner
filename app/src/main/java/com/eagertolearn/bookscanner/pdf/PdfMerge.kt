package com.eagertolearn.bookscanner.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import java.io.File

/**
 * Sequential page merge with one source document open at a time.
 *
 * `PDFMergerUtility` in its default `PDFBOX_LEGACY_MODE` (pdfbox-android 2.0.27) loads every source
 * up front and keeps all of them open until the destination is saved. Each source is read through
 * `RandomAccessBufferedFileInputStream`, whose page cache holds up to 1000 × 4KB, so heap use
 * grows by about 4MB per staged spread (a 100-spread volume needs ~400MB of heap). Its
 * `OPTIMIZE_RESOURCES_MODE` closes each source but drops `destinationDocumentInformation`, which
 * holds the last-spread marker used for idempotency.
 *
 * This copies the optimized mode's page loop: [PDFCloneUtility] deep-copies every stream into the
 * destination's scratch file, so a source can be closed as soon as its pages are cloned. Heap stays
 * at one source cache plus the destination's page dictionaries.
 *
 * Disk: the destination scratch file and the output each hold about one copy of the merged
 * content while the sources still exist. See [requiredFreeBytes].
 */
internal object PdfMerge {

    /** Headroom above the two merged copies, for PDFBox's per-source scratch and xref. */
    private const val MERGE_MARGIN_BYTES = 64L * 1024L * 1024L

    fun requiredFreeBytes(sources: List<File>): Long =
        2L * sources.sumOf { it.length() } + MERGE_MARGIN_BYTES

    fun mergeSequentially(
        sources: List<File>,
        destination: File,
        tempDir: File,
        info: PDDocumentInformation
    ) {
        val memSetting = MemoryUsageSetting.setupTempFileOnly().setTempDir(tempDir)
        PDDocument(memSetting).use { dest ->
            for (sourceFile in sources) {
                PDDocument.load(sourceFile, memSetting).use { src ->
                    // One cloner per source: its identity map would otherwise keep every closed
                    // source's object graph reachable until the end of the merge.
                    val cloner = PDFCloneUtility(dest)
                    for (page in src.pages) {
                        val newPage = PDPage(cloner.cloneForNewDocument(page.cosObject) as COSDictionary)
                        newPage.cropBox = page.cropBox
                        newPage.mediaBox = page.mediaBox
                        newPage.rotation = page.rotation
                        val resources = page.resources
                        newPage.resources = if (resources != null) {
                            PDResources(cloner.cloneForNewDocument(resources) as COSDictionary)
                        } else {
                            PDResources()
                        }
                        dest.addPage(newPage)
                    }
                }
            }
            dest.documentInformation = info
            dest.save(destination)
        }
    }
}
