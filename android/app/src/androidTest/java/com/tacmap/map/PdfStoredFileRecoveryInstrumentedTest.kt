package com.tacmap.map

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.calibration.PdfRenderMeta
import com.tacmap.calibration.PdfStoredFile
import com.tacmap.calibration.PlaneAffine
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.TileSource
import com.tacmap.map.render.pdf.PdfRenderExecutor
import com.tacmap.map.render.pdf.PdfRenderFailure
import com.tacmap.map.render.pdf.PdfRenderGuard
import com.tacmap.map.render.pdf.PdfRenderSessions
import com.tacmap.map.render.pdf.PdfRenderStatus
import com.tacmap.map.render.pdf.PdfTileSource
import com.tacmap.map.render.pdf.TileCoverage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * TST-R2-2 + AND-R2-2 + the device half of OD2-R2-4, through the real PdfMapRuntime the view
 * model owns: a session pinned to the calibrated bytes turns missing or swapped bytes into a
 * sticky cannotOpen on the source, Try Again recovers once the right file is back, a healthy
 * session reused by Try Again still gets checked, and so does a page reopen after a trim.
 * Once failed the source keeps its cache key and base fallback level and loads give null
 */
@RunWith(AndroidJUnit4::class)
class PdfStoredFileRecoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as Application
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(context.cacheDir, "recov-${System.nanoTime()}")
    private lateinit var runtime: PdfMapRuntime

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        PdfRenderSessions.init(context)
        PdfRenderExecutor.foreground = true
        dir.mkdirs()
        runtime = PdfMapRuntime(app, scope, PdfRenderGuard(File(dir, "guard.json"))) { true }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync { runtime.release() }
        scope.cancel()
        dir.deleteRecursively()
    }

    /** a 600 x 400 pt sheet with a grid. [variant] changes the ink (and the length) so the bytes differ */
    private fun inked(file: File, variant: Int) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(600f, 400f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { ink ->
                ink.setStrokingColor(if (variant == 0) 0.9f else 0.1f, 0.1f, if (variant == 0) 0.1f else 0.9f)
                ink.setLineWidth(1.5f)
                for (k in 0..(12 + variant * 5)) {
                    ink.moveTo(k * 40f, 0f); ink.lineTo(k * 40f, 400f)
                    ink.moveTo(0f, k * 30f); ink.lineTo(600f, k * 30f)
                }
                ink.stroke()
            }
            doc.save(file)
        }
    }

    /**
     * swap the file the way a restore or a user would: new inode, renamed over the top. an in
     * place write under an open PdfRenderer could hand pdfium torn bytes, that's not what we test
     */
    private fun replace(file: File, write: (File) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        write(tmp)
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun source(file: File, key: String): PdfMapSource {
        val georef = PdfGeoreference(
            page = 0,
            crs = GeoCrs.Geographic,
            datum = GeoDatums.WGS84,
            affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
            crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
            origin = GeorefOrigin.FIDUCIARIES,
        )
        return PdfMapSource(
            uri = Uri.fromFile(file),
            displayName = "recov",
            kind = MapSourceKind.CALIBRATED_PDF,
            calibration = Calibration.Parsed(georef),
            geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
            render = PdfRenderMeta(contentKey = key),
        )
    }

    private fun live(pdf: PdfMapSource): PdfTileSource {
        var s: PdfTileSource? = null
        instr.runOnMainSync { s = runtime.tileSource(pdf, 2f) }
        return s!!
    }

    /** what the Try Again button does */
    private fun tryAgain(pdf: PdfMapSource): PdfTileSource {
        var s: PdfTileSource? = null
        instr.runOnMainSync {
            runtime.retry()
            s = runtime.tileSource(pdf, 2f)
        }
        return s!!
    }

    private fun tileAt(s: PdfTileSource, z: Int): TileIndex {
        val l = s.footprint.level(z)
        val i = (0 until l.count).firstOrNull { l.coverage[it] == TileCoverage.INSIDE }
            ?: (0 until l.count).first { l.coverage[it] != TileCoverage.OUTSIDE }
        return TileIndex(z, l.xs[i], l.ys[i])
    }

    private fun want(s: PdfTileSource, t: TileIndex) {
        val n = Math.scalb(1.0, t.z)
        s.onWanted(listOf(t), t.z, (t.x + 0.5) / n, (t.y + 0.5) / n)
    }

    private suspend fun awaitStatus(pred: (PdfRenderStatus) -> Boolean): PdfRenderStatus =
        withTimeout(15_000) { runtime.status.first(pred) }

    private suspend fun drawn(s: PdfTileSource, t: TileIndex): Boolean {
        want(s, t)
        val bmp = withTimeout(15_000) { s.loadTile(t) }
        return bmp != null && bmp !== TileSource.EMPTY
    }

    @Test
    fun pinnedBytesMissingOrSwappedStayCannotOpenUntilTryAgainWithTheRightFile() = runBlocking {
        val pdf = File(dir, "sheet.pdf")
        inked(pdf, 0)
        val key = PdfStoredFile.contentKey(pdf)!!
        val original = pdf.readBytes()
        val src = source(pdf, key)
        val cannotOpen = PdfRenderStatus.Failed(PdfRenderFailure.CANNOT_OPEN)

        // missing at restore
        assertTrue(pdf.delete())
        var s = live(src)
        assertEquals(cannotOpen, awaitStatus { it is PdfRenderStatus.Failed })
        val t = tileAt(s, s.baseMaxZoom)
        want(s, t)
        assertNull(s.loadTile(t))

        // the right file back: sticky until Try Again
        replace(pdf) { it.writeBytes(original) }
        delay(500)
        assertEquals(cannotOpen, runtime.status.value)
        assertNull(s.loadTile(t))
        s = tryAgain(src)
        assertTrue("Try Again draws once the file is back", drawn(s, t))
        assertEquals(PdfRenderStatus.Ready, awaitStatus { it == PdfRenderStatus.Ready })

        // swapped bytes while the session is healthy: Try Again reuses it and still checks (AND-R2-2)
        val healthy = s.session
        replace(pdf) { inked(it, 1) }
        s = tryAgain(src)
        assertSame("the healthy session got reused", healthy, s.session)
        assertEquals(cannotOpen, awaitStatus { it is PdfRenderStatus.Failed })
        assertNull(s.loadTile(t))

        // right bytes again
        replace(pdf) { it.writeBytes(original) }
        s = tryAgain(src)
        assertTrue(drawn(s, t))
        awaitStatus { it == PdfRenderStatus.Ready }
        val key0 = s.cacheKey

        // swapped under an open source: a trim closes the page, the reopen checks again (AND-R2-2)
        replace(pdf) { inked(it, 1) }
        PdfRenderSessions.trimPages()
        delay(800)
        val deep = tileAt(s, s.baseMaxZoom + 1)
        want(s, deep)
        assertNull("the vector draw after the reopen gets nothing", withTimeout(15_000) { s.loadTile(deep) })
        assertEquals(cannotOpen, awaitStatus { it is PdfRenderStatus.Failed })

        // OD2-R2-4 device half: still attached with the same identity, so the view's cache and
        // the base level fallback stay, but nothing new comes out
        assertEquals(key0, s.cacheKey)
        assertNotNull("base fallback level still there", s.fallbackZoom(deep.z))
        assertNull(s.loadTile(t))
        assertNull(s.loadTile(deep))
    }
}
