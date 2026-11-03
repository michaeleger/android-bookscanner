package com.eagertolearn.bookscanner.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PdfMergeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun mergesPagesInOrderAndKeepsStreamsAfterSourcesClose() {
        val sources = (1..5).map { spread -> writeSpread(spread) }
        val out = tmp.newFile("Book_Part01.pdf.tmp")

        PdfMerge.mergeSequentially(
            sources = sources,
            destination = out,
            tempDir = tmp.root,
            info = PDDocumentInformation().apply { setCustomMetadataValue("BookScannerLastSpread", "5") }
        )

        // Sources are gone before the output is read back, so nothing in it can still point at them.
        sources.forEach { assertTrue(it.delete()) }

        PDDocument.load(out).use { doc ->
            assertEquals(10, doc.numberOfPages)
            assertEquals("5", doc.documentInformation.getCustomMetadataValue("BookScannerLastSpread"))
            doc.pages.forEachIndexed { i, page ->
                val spread = i / 2 + 1
                val side = i % 2
                assertEquals(400f + side, page.mediaBox.width, 0f)
                val xobj = page.resources.cosObject
                    .getCOSDictionary(COSName.XOBJECT)
                    .getDictionaryObject(COSName.getPDFName("Im0")) as COSStream
                val bytes = xobj.createRawInputStream().use { it.readBytes() }
                assertArrayEquals(payload(spread, side), bytes)
            }
        }
    }

    @Test
    fun appendingToAnExistingVolumeKeepsItsPagesFirst() {
        val firstVolume = tmp.newFile("Book_Part01.pdf")
        PdfMerge.mergeSequentially(
            listOf(writeSpread(1), writeSpread(2)),
            firstVolume,
            tmp.root,
            PDDocumentInformation()
        )
        val out = tmp.newFile("Book_Part01.pdf.tmp")
        PdfMerge.mergeSequentially(listOf(firstVolume, writeSpread(3)), out, tmp.root, PDDocumentInformation())

        PDDocument.load(out).use { doc ->
            assertEquals(6, doc.numberOfPages)
            val last = doc.getPage(5).resources.cosObject
                .getCOSDictionary(COSName.XOBJECT)
                .getDictionaryObject(COSName.getPDFName("Im0")) as COSStream
            assertArrayEquals(payload(3, 1), last.createRawInputStream().use { it.readBytes() })
        }
    }

    @Test
    fun requiredFreeBytesIsTwiceTheInputsPlusMargin() {
        val a = tmp.newFile("a.pdf").apply { writeBytes(ByteArray(1000)) }
        val b = tmp.newFile("b.pdf").apply { writeBytes(ByteArray(3000)) }
        assertEquals(8000L + 64L * 1024L * 1024L, PdfMerge.requiredFreeBytes(listOf(a, b)))
    }

    /** A 2-page spread PDF with a distinct opaque stream per page, standing in for the page JPEG. */
    private fun writeSpread(spread: Int): File {
        val file = tmp.newFile(String.format("spread_%04d.pdf", spread))
        PDDocument().use { doc ->
            for (side in 0..1) {
                val page = PDPage(PDRectangle(400f + side, 600f))
                val image = doc.document.createCOSStream()
                image.createRawOutputStream().use { it.write(payload(spread, side)) }
                image.setItem(COSName.TYPE, COSName.XOBJECT)
                image.setItem(COSName.SUBTYPE, COSName.getPDFName("Form"))
                image.setItem(COSName.BBOX, PDRectangle(1f, 1f).cosArray)
                val resources = PDResources()
                resources.cosObject.setItem(
                    COSName.XOBJECT,
                    com.tom_roush.pdfbox.cos.COSDictionary().apply { setItem(COSName.getPDFName("Im0"), image) }
                )
                page.resources = resources
                doc.addPage(page)
            }
            doc.save(file)
        }
        return file
    }

    private fun payload(spread: Int, side: Int): ByteArray =
        ByteArray(64 * 1024) { i -> ((i * 31 + spread * 7 + side * 13) and 0xFF).toByte() }
}
