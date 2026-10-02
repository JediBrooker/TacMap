package com.tacmap.calibration

import com.tacmap.map.MapImportPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * D5-15: PdfInspector is the only place an OutOfMemoryError / StackOverflowError
 * is caught, and it becomes tooComplex, never success. Anything else PDFBox throws
 * is a typed failure too, not an exception leaking the picked file's path upwards.
 */
class PdfInspectorBoundaryTest {
    private fun failure(t: Throwable): ImportError {
        val r = PdfInspector.guarded { throw t }
        assertTrue("$t -> $r", r is InspectionResult.Failed)
        return (r as InspectionResult.Failed).failure.error
    }

    @Test
    fun runawayParsesAreTooComplexNeverSuccess() {
        assertEquals(ImportError.TOO_COMPLEX, failure(OutOfMemoryError("Java heap space")))
        assertEquals(ImportError.TOO_COMPLEX, failure(StackOverflowError()))
        // the 64 MiB PDFBox scratch cap, straight or wrapped
        assertEquals(ImportError.TOO_COMPLEX, failure(IOException("Maximum allowed scratch file memory exceeded.")))
        assertEquals(ImportError.TOO_COMPLEX, failure(IOException("read failed", IOException("Maximum allowed scratch file memory exceeded."))))
    }

    @Test
    fun junkIsAnInvalidPdf() {
        assertEquals(ImportError.INVALID_PDF, failure(IOException("Error: End-of-File, expected line")))
        assertEquals(ImportError.INVALID_PDF, failure(ClassCastException("COSInteger cannot be cast to COSArray")))
        assertEquals(ImportError.INVALID_PDF, failure(IllegalArgumentException("/private/x/Sheet.pdf")))
    }

    @Test
    fun aCleanResultAndACancelPassStraightThrough() {
        val ok = InspectionResult.Ok(PdfInspection(1, emptyList()))
        assertSame(ok, PdfInspector.guarded { ok })
        // a cancel from the watchdog isn't swallowed into a failure
        val cancel = runCatching { PdfInspector.guarded { throw InterruptedException("import cancelled") } }.exceptionOrNull()
        assertTrue("$cancel", cancel is InterruptedException)
    }

    @Test
    fun thePickerOnlyBadgesScannedPagesWithARejectedGeoref() {
        val pages = (0 until 60).map { i ->
            val g = when (i) {
                2, 55 -> GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED)
                else -> GeoPdfGeorefResult.NoGeoreference
            }
            InspectedPage(i, listOf(0.0, 0.0, 100.0, 100.0), null, 0, g)
        }
        // page 55 is past the 50 page georef scan, it can't have been rejected by it
        assertEquals(listOf(2), MapImportPipeline.rejectedBadges(PdfInspection(60, pages)))
    }

    @Test
    fun aPickedPageKeepsItsOwnBoxRotationAndIssue() {
        val page = InspectedPage(
            index = 3, mediaBox = listOf(0.0, 0.0, 800.0, 600.0), cropBox = listOf(10.0, 20.0, 790.0, 580.0), rotate = -90,
            georef = GeoPdfGeorefResult.Rejected(GeorefRejectReason.RMS_GATE),
        )
        val info = requireNotNull(MapImportPipeline.pdfInfo(7, page, null))
        assertEquals(3, info.pageIndex)
        assertEquals(7, info.pageCount)
        assertEquals(270, info.rotate)
        assertEquals(listOf(listOf(10.0, 20.0), listOf(790.0, 20.0), listOf(790.0, 580.0), listOf(10.0, 580.0)), info.pageBox)
        assertEquals("rmsGate", info.embeddedIssue)
        assertEquals(null, info.embedded)
        assertTrue(File("x").name.isNotEmpty())
    }
}
