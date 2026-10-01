package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw user space -> PdfRenderer page space mapping. The expected renderer
 * numbers below are what pdfium actually drew on the API 36 emulator for the
 * testdata/geopdf sheets (PdfRendererConventionInstrumentedTest re-measures them
 * on device), so the pure math is pinned to the real renderer, not to itself.
 */
class PdfPageGeometryTest {
    // boxes exactly as the files write them (3 dp)
    private val sf = PdfPageGeometry(PdfBox(0.0, 0.0, 824.315, 1051.087), null, 0, 824, 1051)
    private val offset = PdfPageGeometry(
        PdfBox(100.0, 150.0, 924.315, 1201.087),
        PdfBox(120.0, 170.0, 904.315, 1181.087),
        0, 784, 1011,
    )
    private val rot90 = PdfPageGeometry(PdfBox(0.0, 0.0, 1051.087, 824.315), null, 90, 824, 1051)
    private val cbr = PdfPageGeometry(PdfBox(0.0, 0.0, 1277.858, 1277.858), null, 0, 1277, 1277)

    private fun assertRenderer(g: PdfPageGeometry, x: Double, y: Double, u: Double, v: Double) {
        val (gu, gv) = g.rawToRenderer(x, y)
        assertEquals("u for $x,$y", u, gu, 1e-3)
        assertEquals("v for $x,$y", v, gv, 1e-3)
        val back = g.rendererToRaw(gu, gv)
        assertEquals(x, back.x, 1e-9)
        assertEquals(y, back.y, 1e-9)
    }

    @Test
    fun matchesWhatPdfiumDrewOnDevice() {
        // grid intersections located in pdfium renders (dH1 under 0.0005 pt)
        assertRenderer(sf, 638.9291339, 752.3149606, 638.6848, 298.7470)
        assertRenderer(sf, 185.3858268, 185.3858268, 185.3147, 865.6292)
        assertRenderer(offset, 738.9291339, 902.3149606, 618.6803, 278.7478)
        assertRenderer(rot90, 298.7716535, 638.9291339, 638.6845, 298.7468)
        assertRenderer(rot90, 752.3149606, 185.3858268, 185.3147, 752.2525)
        assertRenderer(cbr, 1149.1653543, 1149.1653543, 1148.3935, 128.6060)
    }

    @Test
    fun cropOriginAndTruncatedSizeAreHonoured() {
        assertEquals(PdfBox(120.0, 170.0, 904.315, 1181.087), offset.visibleBox)
        // the crop's lower left is the renderer's bottom left, not raw (0,0)
        assertRenderer(offset, 120.0, 170.0, 0.0, 1011.0)
        assertRenderer(offset, 904.315, 1181.087, 784.0, 0.0)
        // crop sticking out of the media box gets clipped like pdfium does
        val clipped = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), PdfBox(-50.0, -50.0, 300.0, 300.0), 0, 300, 300)
        assertEquals(PdfBox(0.0, 0.0, 300.0, 300.0), clipped.visibleBox)
    }

    @Test
    fun everyRotationLandsTheRawCornersWhereTheViewerShowsThem() {
        val media = PdfBox(10.0, 20.0, 610.0, 420.0)
        // /Rotate is clockwise: raw bottom left ends up top left at 90, etc
        val cases = mapOf(
            0 to listOf(PagePoint(10.0, 420.0), PagePoint(610.0, 420.0), PagePoint(610.0, 20.0), PagePoint(10.0, 20.0)),
            90 to listOf(PagePoint(10.0, 20.0), PagePoint(10.0, 420.0), PagePoint(610.0, 420.0), PagePoint(610.0, 20.0)),
            180 to listOf(PagePoint(610.0, 20.0), PagePoint(10.0, 20.0), PagePoint(10.0, 420.0), PagePoint(610.0, 420.0)),
            270 to listOf(PagePoint(610.0, 420.0), PagePoint(610.0, 20.0), PagePoint(10.0, 20.0), PagePoint(10.0, 420.0)),
        )
        for ((rotation, expected) in cases) {
            val swap = rotation % 180 != 0
            val g = PdfPageGeometry(media, null, rotation, if (swap) 400 else 600, if (swap) 600 else 400)
            g.bitmapCornersRaw().zip(expected).forEachIndexed { i, (a, e) ->
                assertEquals("rot $rotation corner $i x", e.x, a.x, 1e-9)
                assertEquals("rot $rotation corner $i y", e.y, a.y, 1e-9)
            }
        }
    }

    @Test
    fun rotationNormalisesLikePdfium() {
        assertEquals(0, PdfPageGeometry.normaliseRotation(0))
        assertEquals(90, PdfPageGeometry.normaliseRotation(90))
        assertEquals(90, PdfPageGeometry.normaliseRotation(450))
        assertEquals(270, PdfPageGeometry.normaliseRotation(-90))
        assertEquals(180, PdfPageGeometry.normaliseRotation(-180))
        // not a multiple of 90: pdfium's int division drops it
        assertEquals(0, PdfPageGeometry.normaliseRotation(45))
        assertEquals(0, PdfPageGeometry.normaliseRotation(-45))
    }

    @Test
    fun rendererTransformUndoesPdfiumsOwnPageMatrix() {
        // want raw (x, y) -> (2x - 5, 300 - 3y) in the bitmap
        val rawToDest = doubleArrayOf(2.0, 0.0, -5.0, 0.0, -3.0, 300.0)
        for (g in listOf(sf, offset, rot90, cbr)) {
            val t = requireNotNull(g.rendererTransformFor(rawToDest))
            for ((x, y) in listOf(130.0 to 180.0, 500.0 to 700.0, 900.0 to 200.0)) {
                val (u, v) = g.rawToRenderer(x, y)
                // pdfium applies its page matrix, then our t
                val px = t[0] * u + t[1] * v + t[2]
                val py = t[3] * u + t[4] * v + t[5]
                assertEquals(2 * x - 5, px, 1e-6)
                assertEquals(300 - 3 * y, py, 1e-6)
            }
        }
        assertNull(sf.rendererTransformFor(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 1.0, 0.0)))
    }

    @Test
    fun junkBoxesAreRefused() {
        assertNull(PdfBox.of(listOf(0.0, 0.0, 0.0, 10.0)))
        assertNull(PdfBox.of(listOf(0.0, 0.0, Double.NaN, 10.0)))
        assertEquals(PdfBox(0.0, 0.0, 10.0, 20.0), PdfBox.of(listOf(10.0, 20.0, 0.0, 0.0)))
        assertFalse(PdfPageGeometry(PdfBox(0.0, 0.0, 10.0, 10.0), null, 45, 10, 10).isValid())
        assertTrue(PdfPageGeometry.rendererOnly(200, 300).isValid())
    }

    @Test
    fun inspectorFallsBackWhenPdfboxAndPdfiumDisagree() {
        val meta = GeoPdfPageData(mediaBox = listOf(0.0, 0.0, 824.315, 1051.087))
        val agreed = PdfDocumentInspector.firstPage(824, 1051, meta)
        assertFalse(agreed.framesDisagree)
        assertEquals(sf.mediaBox, agreed.geometry.mediaBox)
        // pdfium says the page is a different size: raw-space georefs can't be trusted
        val mismatch = PdfDocumentInspector.firstPage(500, 500, meta)
        assertTrue(mismatch.framesDisagree)
        assertEquals(PdfPageGeometry.rendererOnly(500, 500), mismatch.geometry)
        // no PDFBox at all: renderer frame, nothing declared
        val none = PdfDocumentInspector.firstPage(500, 700, null)
        assertEquals(GeoPdfGeorefResult.NoGeoreference, none.georeference())
    }
}
