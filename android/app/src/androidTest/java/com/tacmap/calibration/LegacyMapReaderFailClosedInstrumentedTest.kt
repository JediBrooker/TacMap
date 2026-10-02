package com.tacmap.calibration

import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * WP4 r1 S3 + S4 through the real legacy stores on device, on their own prefs + files dir:
 * a legacy PDF record that's there but won't open blocks the migration with nothing cleared,
 * and an authenticated read that names nothing comes back Absent (write empty + clear).
 */
@RunWith(AndroidJUnit4::class)
class LegacyMapReaderFailClosedInstrumentedTest {
    private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(app.cacheDir, "legacy-reader-${System.nanoTime()}").apply { mkdirs() }
    private val prefix = "legacy-reader-test-${System.nanoTime()}-"
    private val isolated = object : ContextWrapper(app) {
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            app.getSharedPreferences(prefix + name, mode)
    }
    private val sessionPrefs get() = isolated.getSharedPreferences("pdf_session", Context.MODE_PRIVATE)

    private fun reader() = LegacyMapReader(isolated, ActiveMapSelectionStore(isolated), PdfSessionStore(isolated))

    @After
    fun tearDown() {
        sessionPrefs.edit().clear().commit()
        root.deleteRecursively()
    }

    @Test
    fun aLegacyPdfRecordThatWontOpenBlocksTheMigrationAndNothingIsCleared() {
        // looks sealed (not '{'), won't authenticate: the session can't be opened
        assertTrue(sessionPrefs.edit().putString("active_pdf", "AAAAbm90IGEgc2VhbGVkIGJsb2I=").commit())
        val r = reader()
        assertTrue(r.hasLegacyState())
        assertEquals(LegacyMapReader.Read.Unavailable, r.read("OSM_TOPO"))
        // fail closed: still there for the next try
        assertTrue(sessionPrefs.contains("active_pdf"))
        assertEquals(
            RestoreMigration.BLOCKED,
            LibraryRestoreRules.plan(LibraryLoad.Empty, LibraryRestoreRules.legacyState(LegacyMapReader.Read.Unavailable)).migration,
        )
    }

    private fun legacySource(file: File, key: String): PdfMapSource {
        val georef = PdfGeoreference(
            page = 0, crs = GeoCrs.Geographic, datum = GeoDatums.WGS84,
            affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
            crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
            origin = GeorefOrigin.FIDUCIARIES,
        )
        return PdfMapSource(uri = Uri.fromFile(file), displayName = "legacy", kind = MapSourceKind.CALIBRATED_PDF,
                            calibration = Calibration.Parsed(georef),
                            geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
                            render = PdfRenderMeta(contentKey = key))
    }

    @Test
    fun aSwappedLegacyPdfNeverRebindsItsSealedCalibrationToReplacementBytes() {
        PDFBoxResourceLoader.init(isolated)
        val file = File(root, "pdf_maps/legacy.pdf").apply { parentFile!!.mkdirs() }
        PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle(600f, 400f))); doc.save(file) }
        val session = PdfSessionStore(isolated)
        assertTrue(session.save(legacySource(file, PdfCalibrationIdentity.contentKey(file)!!)))
        val originalRecord = sessionPrefs.getString("active_pdf", null)
        file.appendText("changed bytes")
        assertEquals(LegacyMapReader.Read.Unavailable, reader().read("OSM_TOPO"))
        assertEquals(originalRecord, sessionPrefs.getString("active_pdf", null))
        assertTrue(file.isFile)
    }

    @Test
    fun aPresentUnreadableLegacyPdfNeverGetsAFabricatedPageCount() {
        val file = File(root, "pdf_maps/invalid.pdf").apply { parentFile!!.mkdirs(); writeText("invalid pdf") }
        val session = PdfSessionStore(isolated)
        assertTrue(session.save(legacySource(file, PdfCalibrationIdentity.contentKey(file)!!)))
        val originalRecord = sessionPrefs.getString("active_pdf", null)
        assertEquals(LegacyMapReader.Read.Unavailable, reader().read("OSM_TOPO"))
        assertEquals(originalRecord, sessionPrefs.getString("active_pdf", null))
        assertTrue(file.isFile)
    }

    @Test
    fun anAuthenticatedReadThatNamesNothingIsAbsentSoTheLibraryGetsWrittenEmpty() {
        // only the old content-keyed calibration library, no selector and no active PDF
        assertTrue(sessionPrefs.edit().putString("pdf_calibrations", "AAAAbGVmdG92ZXI=").commit())
        val r = reader()
        assertTrue(r.hasLegacyState())
        val read = r.read("OSM_TOPO")
        assertEquals(LegacyMapReader.Read.Absent, read)
        assertEquals(
            RestoreMigration.WRITE_EMPTY_AND_CLEAR,
            LibraryRestoreRules.plan(LibraryLoad.Empty, LibraryRestoreRules.legacyState(read)).migration,
        )
        // what the VM does next: write empty, then clear, after which nothing's left to migrate
        assertTrue(ImportedMapLibraryStore(root).write(LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO")))
        assertTrue(r.clearAfterMigration())
        assertTrue(!r.hasLegacyState())
        assertTrue(ImportedMapLibraryStore(root).load() is LibraryLoad.Loaded)
    }
}
