package com.tacmap.calibration

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Schema 1 sessions through the real store on device: migrate (re-parse / refit /
 * mark uncalibrated), re-seal as schema 2, and come back from a plain load()
 * without migrating again. Runs on its own prefs + files dir so the app's real
 * session on the emulator isn't touched.
 */
@RunWith(AndroidJUnit4::class)
class PdfSessionMigrationInstrumentedTest {
    private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val root = File(app.cacheDir, "pdf-migration-${System.nanoTime()}")
    private val isolated = object : ContextWrapper(app) {
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            app.getSharedPreferences("migration-test-$name", mode)
    }
    private val sheets by lazy { PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }.associateBy { it.str("id") } }

    @Before
    fun setUp() {
        PdfGeorefFixture.readBytes = { name -> assets.open(name).use { it.readBytes() } }
        PDFBoxResourceLoader.init(app)
        File(root, "pdf_maps").mkdirs()
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("migration-test-pdf_session", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    private fun install(id: String): File {
        val out = File(root, "pdf_maps/$id.pdf")
        out.writeBytes(PdfGeorefFixture.readBytes(sheets.getValue(id).str("file")))
        return out
    }

    private val sfCoverage = Wgs84Bounds(Wgs84Coordinate(37.72, -122.48), Wgs84Coordinate(37.81, -122.40))

    @Test
    fun handCalibratedV1SessionIsRefittedInUtmAndReloadsAsV2() {
        val file = install("sf_plain")
        val sheet = sheets.getValue("sf_plain")
        val truth = sheet.obj("truth")
        // what the v1 store wrote: renderer-relative y-up points + WGS84 lat/lon of the typed MGRS
        val fit = FiduciaryFitter.fit(
            truth.arr("fiducialTargets").map { it.jsonObject }.map {
                FiduciaryPoint(PdfGeorefFixture.point(it["page"]!!), FiduciaryReferenceParser.parse(it.str("label"))!!)
            },
            GeoDatums.WGS84, doubleArrayOf(0.0, 0.0, 824.315, 1051.087),
        )!!.georeference(PdfBox(0.0, 0.0, 824.315, 1051.087).corners())!!
        val fids = truth.arr("fiducialTargets").map { it.jsonObject }.map {
            val p = PdfGeorefFixture.point(it["page"]!!)
            val w = fit.toWGS84(p.x, p.y)!!
            Fiduciary(pdfX = p.x / 824.315 * 824, pdfY = p.y / 1051.087 * 1051, mgrs = it.str("label"), latitude = w.latitude, longitude = w.longitude)
        }
        val v1 = PersistedPdfSource(
            fileName = file.name, displayName = "sf", pageWidth = 824, pageHeight = 1051,
            sourceKind = "CALIBRATED_PDF", calibrationKind = "fiduciaries",
            calibration = PersistedCalibration(fids, AffineTransform2D(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)),
            coverage = sfCoverage,
        )
        val store = PdfSessionStore(isolated)
        val migrated = requireNotNull(store.restoreOrMigrate(v1, file))
        val cal = migrated.calibration as Calibration.Fiduciaries
        assertEquals(GeorefOrigin.FIDUCIARIES, cal.georef.origin)
        assertEquals(10, (cal.georef.crs as GeoCrs.TransverseMercator).utmZone)
        assertGrid(sheet, cal.georef)

        // re-saved as schema 2: a plain load() restores it without migrating again
        val reloaded = requireNotNull(store.load())
        val again = reloaded.calibration as Calibration.Fiduciaries
        assertEquals(cal.georef.affine, again.georef.affine)
        assertEquals(cal.fids, again.fids)
        assertEquals(migrated.geometry, reloaded.geometry)
        // and it went into the content-keyed library in raw page space
        val library = requireNotNull(store.libraryFiduciaries(file))
        assertTrue(library.rawPageSpace)
    }

    @Test
    fun geoPdfV1SessionIsReparsedFromTheFile() {
        val file = install("sf_iso")
        val v1 = PersistedPdfSource(
            fileName = file.name, displayName = "sf iso", pageWidth = 824, pageHeight = 1051,
            sourceKind = "GEO_PDF", calibrationKind = "parsed", calibrationCrs = "",
            calibration = PersistedCalibration(emptyList(), AffineTransform2D(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)),
            coverage = sfCoverage,
        )
        val store = PdfSessionStore(isolated)
        val migrated = requireNotNull(store.restoreOrMigrate(v1, file))
        assertEquals(MapSourceKind.GEO_PDF, migrated.kind)
        val georef = (migrated.calibration as Calibration.Parsed).georef
        assertEquals(GeorefOrigin.ADOBE_VP, georef.origin)
        PdfGeorefFixture.assertChecks("sf_iso migrated", georef, sheets.getValue("sf_iso").obj("expected").arr("checks"), 1e-3, 1e-3, 1e-2)
        assertEquals(georef.affine, (requireNotNull(store.load()).calibration as Calibration.Parsed).georef.affine)
    }

    @Test
    fun cameraFallbackV1SessionComesBackUncalibratedAndSaysSo() {
        val file = install("rot5_plain")
        val v1 = PersistedPdfSource(
            fileName = file.name, displayName = "scan", pageWidth = 900, pageHeight = 1106,
            sourceKind = "CALIBRATED_PDF", calibrationKind = "none", coverage = sfCoverage,
        )
        val store = PdfSessionStore(isolated)
        val migrated = requireNotNull(store.restoreOrMigrate(v1, file))
        assertFalse(migrated.isGeoreferenced)
        assertEquals(PdfGeorefIssue.LegacyPlacement, migrated.georefIssue)
        assertEquals(GeorefOrigin.PROVISIONAL, migrated.placement?.origin)
        // provisional sits where they last saw it, labelled, not passed off as a real fit
        assertTrue(requireNotNull(migrated.coverage).contains(sfCoverage.center.latitude, sfCoverage.center.longitude))
        val reloaded = requireNotNull(store.load())
        assertFalse(reloaded.isGeoreferenced)
        assertEquals(PdfGeorefIssue.LegacyPlacement, reloaded.georefIssue)
        assertNotNull(reloaded.provisional)
    }

    private fun activeBlob(): String? =
        app.getSharedPreferences("migration-test-pdf_session", Context.MODE_PRIVATE).getString("active_pdf", null)

    @Test
    fun v1SessionIsDeferredToMigrateNotParsedOnLoad() {
        val file = install("sf_iso")
        val v1 = PersistedPdfSource(
            fileName = file.name, displayName = "sf iso", pageWidth = 824, pageHeight = 1051,
            sourceKind = "GEO_PDF", calibrationKind = "parsed", coverage = sfCoverage,
        )
        val store = PdfSessionStore(isolated)
        // the fast path hands it back untouched, the PDF hasn't been opened
        val pending = store.restoreOrDefer(v1, file, stored = activeBlob())
        assertTrue(pending is PdfSessionLoad.NeedsMigration)
        assertEquals(file, (pending as PdfSessionLoad.NeedsMigration).file)
        assertEquals(null, activeBlob())
        // migrate does the work (off main in the app) and re-saves as schema 2
        val migrated = requireNotNull(store.migrate(pending))
        assertEquals(MapSourceKind.GEO_PDF, migrated.kind)
        val again = store.loadSession()
        assertTrue("schema 2 now, no second migration", again is PdfSessionLoad.Ready)
        assertEquals(file.canonicalFile, store.activeFile()?.canonicalFile)
    }

    @Test
    fun migrationNeverOverwritesASessionSavedWhileItRan() {
        val store = PdfSessionStore(isolated)
        // the user's newer session, saved while the old one was migrating
        val newer = install("sf_plain")
        val newerV1 = PersistedPdfSource(
            fileName = newer.name, displayName = "newer", pageWidth = 824, pageHeight = 1051,
            sourceKind = "CALIBRATED_PDF", calibrationKind = "none", coverage = sfCoverage,
        )
        requireNotNull(store.restoreOrMigrate(newerV1, newer))
        val blob = requireNotNull(activeBlob())

        val older = install("sf_iso")
        val olderV1 = PersistedPdfSource(
            fileName = older.name, displayName = "older", pageWidth = 824, pageHeight = 1051,
            sourceKind = "GEO_PDF", calibrationKind = "parsed", coverage = sfCoverage,
        )
        // loaded back when nothing was saved yet
        val pending = store.restoreOrDefer(olderV1, older, stored = null) as PdfSessionLoad.NeedsMigration
        assertEquals("stale migration is dropped", null, store.migrate(pending))
        assertEquals("newer session untouched", blob, activeBlob())
        assertEquals("newer", requireNotNull(store.load()).displayName)
    }

    @Test
    fun v2SessionWhoseGeorefNoLongerDecodesIsReparsedFromTheFile() {
        val file = install("sf_iso")
        val store = PdfSessionStore(isolated)
        val good = requireNotNull(
            store.restoreOrMigrate(
                PersistedPdfSource(
                    fileName = file.name, displayName = "sf iso", pageWidth = 824, pageHeight = 1051,
                    sourceKind = "GEO_PDF", calibrationKind = "parsed", coverage = sfCoverage,
                ),
                file,
            ),
        )
        val georef = (good.calibration as Calibration.Parsed).georef
        // what a later codec can't read any more: a crs kind it doesn't know
        val broken = PersistedPdfSource(
            schemaVersion = 2, fileName = file.name, displayName = "sf iso", pageWidth = good.geometry.rendererWidth,
            pageHeight = good.geometry.rendererHeight, sourceKind = "GEO_PDF", calibrationKind = "parsed",
            coverage = sfCoverage, georef = PdfGeoreferenceCodec.encode(georef).copy(crs = PersistedCrs("albers")),
            geometry = good.geometry,
        )
        val pending = store.restoreOrDefer(broken, file, stored = activeBlob())
        assertTrue("re-read, not restored uncalibrated", pending is PdfSessionLoad.NeedsMigration)
        val recovered = requireNotNull(store.migrate(pending as PdfSessionLoad.NeedsMigration))
        assertEquals(MapSourceKind.GEO_PDF, recovered.kind)
        val again = (recovered.calibration as Calibration.Parsed).georef
        assertEquals(georef.affine, again.affine)
        PdfGeorefFixture.assertChecks("sf_iso recovered", again, sheets.getValue("sf_iso").obj("expected").arr("checks"), 1e-3, 1e-3, 1e-2)
    }

    @Test
    fun v2HandCalibrationWhoseGeorefNoLongerDecodesIsRefitFromItsRawPoints() {
        val file = install("sf_plain")
        val sheet = sheets.getValue("sf_plain")
        val store = PdfSessionStore(isolated)
        val uncal = requireNotNull(
            store.restoreOrMigrate(
                PersistedPdfSource(
                    fileName = file.name, displayName = "sf", pageWidth = 824, pageHeight = 1051,
                    sourceKind = "CALIBRATED_PDF", calibrationKind = "none", coverage = sfCoverage,
                ),
                file,
            ),
        )
        val crop = uncal.geometry.visibleCrop()
        val media = PdfGeorefFixture.doubles(sheet["mediaBox"]!!)
        val truth = requireNotNull(
            FiduciaryFitter.fit(
                sheet.obj("truth").arr("fiducialTargets").map { it.jsonObject }.map {
                    FiduciaryPoint(PdfGeorefFixture.point(it["page"]!!), FiduciaryReferenceParser.parse(it.str("label"))!!)
                },
                GeoDatums.WGS84, media.toDoubleArray(),
            )?.georeference(crop),
        )
        val raw = sheet.obj("truth").arr("fiducialTargets").map { it.jsonObject }.map {
            val p = PdfGeorefFixture.point(it["page"]!!)
            val w = truth.toWGS84(p.x, p.y)!!
            Fiduciary(pdfX = p.x, pdfY = p.y, mgrs = it.str("label"), latitude = w.latitude, longitude = w.longitude)
        }
        val broken = PersistedPdfSource(
            schemaVersion = 2, fileName = file.name, displayName = "sf", pageWidth = uncal.geometry.rendererWidth,
            pageHeight = uncal.geometry.rendererHeight, sourceKind = "CALIBRATED_PDF", calibrationKind = "fiduciaries",
            calibration = PersistedCalibration(raw, pageSpace = "raw"), coverage = sfCoverage,
            georef = PdfGeoreferenceCodec.encode(truth).copy(datum = PersistedDatum("MARS2000")),
            geometry = uncal.geometry,
        )
        val pending = store.restoreOrDefer(broken, file, stored = activeBlob()) as PdfSessionLoad.NeedsMigration
        val recovered = requireNotNull(store.migrate(pending))
        val cal = recovered.calibration as Calibration.Fiduciaries
        assertEquals(raw, cal.fids)
        assertGrid(sheet, cal.georef)
        assertTrue(store.loadSession() is PdfSessionLoad.Ready)
    }

    private fun assertGrid(sheet: kotlinx.serialization.json.JsonObject, georef: PdfGeoreference) {
        for (c in sheet.arr("gridChecks").map { it.jsonObject }) {
            val p = PdfGeorefFixture.point(c["page"]!!)
            val w = PdfGeorefFixture.doubles(c["wgs84"]!!)
            val got = requireNotNull(georef.toWGS84(p.x, p.y))
            val err = PdfGeorefFixture.metres(w[0], w[1], got.latitude, got.longitude)
            assertTrue("${c.str("label")} off by $err m", err < 0.05)
        }
    }
}
