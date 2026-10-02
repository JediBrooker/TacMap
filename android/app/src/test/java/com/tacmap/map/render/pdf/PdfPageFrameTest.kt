package com.tacmap.map.render.pdf

import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfPageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.floor

/** raw user space -> PdfRenderer page px for every /Rotate, box offset and size mode */
class PdfPageFrameTest {
    private val boxes = listOf(
        // plain A4ish, an offset media box with an inset crop, and fractional A series
        Triple(PdfBox(0.0, 0.0, 612.0, 792.0), null, "letter"),
        Triple(PdfBox(100.0, 100.0, 700.0, 500.0), PdfBox(120.0, 115.0, 680.0, 490.0), "offset"),
        Triple(PdfBox(0.0, 0.0, 595.2756, 841.8898), null, "a4"),
    )

    private fun geometry(media: PdfBox, crop: PdfBox?, rotation: Int): PdfPageGeometry {
        val vis = crop?.let { media.intersect(it) } ?: media
        val w = if (rotation % 180 == 0) vis.width else vis.height
        val h = if (rotation % 180 == 0) vis.height else vis.width
        return PdfPageGeometry(media, crop, rotation, floor(w).toInt(), floor(h).toInt())
    }

    @Test
    fun roundTripsAndPutsTheDisplayedTopLeftAtTheOrigin() {
        for ((media, crop, name) in boxes) for (rot in listOf(0, 90, 180, 270)) for (mode in DisplaySizeMode.values()) {
            val g = geometry(media, crop, rot)
            val f = PdfPageFrame(g, g.rendererWidth, g.rendererHeight, mode)
            assertNull("$name r$rot $mode validate", f.validate())
            val v = g.visibleBox
            for ((x, y) in listOf(v.llx to v.lly, v.urx to v.ury, (v.llx + v.urx) / 2 to v.lly + 7.3)) {
                val u = f.userToRenderer.mapX(x, y)
                val w = f.userToRenderer.mapY(x, y)
                assertEquals("$name r$rot x", x, f.rendererToUser.mapX(u, w), 1e-9)
                assertEquals("$name r$rot y", y, f.rendererToUser.mapY(u, w), 1e-9)
            }
            // clockwise rotation: which raw corner ends up top left as viewed
            val (tlx, tly) = when (rot) {
                90 -> v.llx to v.lly
                180 -> v.urx to v.lly
                270 -> v.urx to v.ury
                else -> v.llx to v.ury
            }
            assertEquals("$name r$rot tl.u", 0.0, f.userToRenderer.mapX(tlx, tly), 1e-9)
            assertEquals("$name r$rot tl.v", 0.0, f.userToRenderer.mapY(tlx, tly), 1e-9)
            // and the opposite corner lands on the display size, int in TRUNCATED, float in FLOAT
            val (brx, bry) = when (rot) {
                90 -> v.urx to v.ury
                180 -> v.llx to v.ury
                270 -> v.llx to v.lly
                else -> v.urx to v.lly
            }
            val ew = if (mode == DisplaySizeMode.TRUNCATED) g.rendererWidth.toDouble() else f.rotatedWidth
            val eh = if (mode == DisplaySizeMode.TRUNCATED) g.rendererHeight.toDouble() else f.rotatedHeight
            assertEquals("$name r$rot br.u", ew, f.userToRenderer.mapX(brx, bry), 1e-9)
            assertEquals("$name r$rot br.v", eh, f.userToRenderer.mapY(brx, bry), 1e-9)
        }
    }

    @Test
    fun truncatedModeIsWp1sPinnedConvention() {
        // WP1 pinned TRUNCATED on API 36 (PdfRendererConventionInstrumentedTest), PdfPageGeometry encodes it
        for ((media, crop, name) in boxes) for (rot in listOf(0, 90, 180, 270)) {
            val g = geometry(media, crop, rot)
            val f = PdfPageFrame(g, g.rendererWidth, g.rendererHeight, DisplaySizeMode.TRUNCATED)
            for ((x, y) in listOf(media.llx + 31.0 to media.lly + 47.5, media.urx - 3.25 to media.ury - 100.0)) {
                val (u, v) = g.rawToRenderer(x, y)
                assertEquals("$name r$rot u", u, f.userToRenderer.mapX(x, y), 1e-9)
                assertEquals("$name r$rot v", v, f.userToRenderer.mapY(x, y), 1e-9)
            }
        }
    }

    @Test
    fun renderMatrixUndoesPdfiumsOwnFrame() {
        val g = geometry(PdfBox(100.0, 100.0, 700.0, 500.0), PdfBox(120.0, 115.0, 680.0, 490.0), 90)
        val f = PdfPageFrame(g, g.rendererWidth, g.rendererHeight, DisplaySizeMode.TRUNCATED)
        val pageToPx = Affine2(1.75, 0.01, -168.5, 0.01, -1.75, 1407.4)
        // pdfium does renderer = userToRenderer(p) then our matrix: the result has to be pageToPx(p)
        val m = f.renderMatrix(pageToPx)
        for ((x, y) in listOf(200.0 to 300.0, 650.0 to 470.0)) {
            val ru = f.userToRenderer.mapX(x, y)
            val rv = f.userToRenderer.mapY(x, y)
            assertEquals(pageToPx.mapX(x, y), m.mapX(ru, rv), 1e-9)
            assertEquals(pageToPx.mapY(x, y), m.mapY(ru, rv), 1e-9)
        }
    }

    @Test
    fun aPageThatIsntTheBoxWeReadFailsClosed() {
        val g = geometry(PdfBox(0.0, 0.0, 612.0, 792.0), null, 0)
        // pdfium opened something 3 pt wider than PDFBox's boxes say (D3-05)
        assertEquals(PdfRenderFailure.PAGE_GEOMETRY, PdfPageFrame(g, 615, 792, DisplaySizeMode.TRUNCATED).validate())
        // a rotation PDFBox didn't see swaps the sizes
        assertEquals(PdfRenderFailure.PAGE_GEOMETRY, PdfPageFrame(g, 792, 612, DisplaySizeMode.TRUNCATED).validate())
        assertNull(PdfPageFrame(g, 612, 792, DisplaySizeMode.FLOAT).validate())
    }

    @Test
    fun probePdfIsAWellFormedTinyPage() {
        val bytes = PdfDisplaySizeProbe.probePdf()
        val text = String(bytes, Charsets.US_ASCII)
        assertNotNull(Regex("/MediaBox \\[0 0 100.5 60.5\\]").find(text))
        // every xref offset points at its "n 0 obj"
        val xref = text.substring(text.indexOf("xref"))
        val offsets = Regex("(\\d{10}) 00000 n").findAll(xref).map { it.groupValues[1].toInt() }.toList()
        assertEquals(4, offsets.size)
        offsets.forEachIndexed { i, off -> assertEquals("${i + 1} 0 obj", text.substring(off, off + "${i + 1} 0 obj".length)) }
        val startxref = Regex("startxref\\n(\\d+)").find(text)!!.groupValues[1].toInt()
        assertEquals("xref", text.substring(startxref, startxref + 4))
    }
}
