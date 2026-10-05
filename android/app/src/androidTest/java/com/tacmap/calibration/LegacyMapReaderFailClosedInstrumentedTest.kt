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
import com.tacmap.util.DataKey
import com.tacmap.util.MissionKeyUnlockRule
import com.tacmap.util.SafeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * WP4 r1 S3/S4 + 3.0.1 s13.1 through the real legacy stores on device, on their own prefs and
 * files dir: a legacy PDF record that's there but won't open, opens on other bytes, or opens on
 * a PDF pdfium can't read is uncertain (the migration salvages, nothing's cleared), and an
 * authenticated read that names nothing comes back Absent (write empty + clear).
 *
 * The key and the sealed-only ledger are test doubles, the real ones go back after each test.
 * gap-android-blockers-empirical-4: the isolated library write used to land in the app's own
 * DataKey ledger, so the app's real (absent) library read as corrupt from then on and every
 * later test that wanted a fresh install broke, depending on test order
 */
@RunWith(AndroidJUnit4::class)
class LegacyMapReaderFailClosedInstrumentedTest {
    // only so the app's own ledger can be asked, before and after
    @get:Rule val missionKey = MissionKeyUnlockRule()
    private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(app.cacheDir, "legacy-reader-${System.nanoTime()}").apply { mkdirs() }
    private val prefix = "legacy-reader-test-${System.nanoTime()}-"
    private val isolated = object : ContextWrapper(app) {
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            app.getSharedPreferences(prefix + name, mode)
    }
    private val sessionPrefs get() = isolated.getSharedPreferences("pdf_session", Context.MODE_PRIVATE)
    private val testKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private val ledger = java.util.Collections.synchronizedSet(HashSet<String>())
    private var realKey: SafeStore.KeyProvider? = null
    private var realPolicy: SafeStore.MigrationPolicy? = null
    private var appLedgerBefore = false

    @Before
    fun isolate() {
        appLedgerBefore = DataKey.isStoreSealedOnly(ImportedMapLibraryStore.LABEL)
        realKey = SafeStore.keyProvider
        realPolicy = SafeStore.migrationPolicy
        SafeStore.keyProvider = SafeStore.KeyProvider { testKey.copyOf() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in ledger
            override fun markSealedOnly(label: String) { ledger += label }
        }
    }

    @After
    fun tearDown() {
        realKey?.let { SafeStore.keyProvider = it }
        realPolicy?.let { SafeStore.migrationPolicy = it }
        sessionPrefs.edit().clear().commit()
        root.deleteRecursively()
        // whatever ran in here, the app's own record of its library never moved
        assertEquals(appLedgerBefore, DataKey.isStoreSealedOnly(ImportedMapLibraryStore.LABEL))
    }

    private fun reader() = LegacyMapReader(isolated, ActiveMapSelectionStore(isolated), PdfSessionStore(isolated))

    private fun uncertain(read: LegacyMapReader.Read): Set<LegacyLibraryState> =
        (read as? LegacyMapReader.Read.Uncertain)?.causes ?: throw AssertionError("not uncertain: $read")

    private fun pdf(rel: String): File {
        PDFBoxResourceLoader.init(isolated)
        val file = File(root, rel).apply { parentFile!!.mkdirs() }
        PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle(600f, 400f))); doc.save(file) }
        return file
    }

    private val georef = PdfGeoreference(
        page = 0, crs = GeoCrs.Geographic, datum = GeoDatums.WGS84,
        affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
        crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
        origin = GeorefOrigin.FIDUCIARIES,
    )

    private fun legacySource(file: File, key: String, calibration: Calibration = Calibration.Parsed(georef)): PdfMapSource =
        PdfMapSource(uri = Uri.fromFile(file), displayName = "legacy", kind = MapSourceKind.CALIBRATED_PDF,
                     calibration = calibration,
                     geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
                     render = PdfRenderMeta(contentKey = key))

    @Test
    fun aLegacyPdfRecordThatWontOpenIsUncertainAndNothingIsCleared() {
        // looks sealed (not '{'), won't authenticate with the key right there
        assertTrue(sessionPrefs.edit().putString("active_pdf", "AAAAbm90IGEgc2VhbGVkIGJsb2I=").commit())
        val r = reader()
        assertTrue(r.hasLegacyState())
        val read = r.read("OSM_TOPO")
        assertEquals(setOf(LegacyLibraryState.SESSION_INVALID), uncertain(read))
        // salvage, never blocked forever: and the record stays where it is
        assertEquals(
            RestoreMigration.SALVAGE,
            LibraryRestoreRules.plan(LibraryLoad.Empty, LibraryRestoreRules.legacyState(read), managedFiles = false).migration,
        )
        assertTrue(sessionPrefs.contains("active_pdf"))
    }

    @Test
    fun aSwappedLegacyPdfNeverRebindsItsSealedCalibrationToReplacementBytes() {
        val file = pdf("pdf_maps/legacy.pdf")
        val session = PdfSessionStore(isolated)
        assertTrue(session.save(legacySource(file, PdfCalibrationIdentity.contentKey(file)!!)))
        val originalRecord = sessionPrefs.getString("active_pdf", null)
        file.appendText("changed bytes")
        val read = reader().read("OSM_TOPO")
        assertEquals(setOf(LegacyLibraryState.PDF_HASH_MISMATCH), uncertain(read))
        // nothing converted from the old session: the file gets adopted and inspected afresh
        assertEquals(null, (read as LegacyMapReader.Read.Uncertain).inputs.pdf)
        assertEquals(originalRecord, sessionPrefs.getString("active_pdf", null))
        assertTrue(file.isFile)
    }

    @Test
    fun aPresentUnreadableLegacyPdfNeverGetsAFabricatedPageCount() {
        val file = File(root, "pdf_maps/invalid.pdf").apply { parentFile!!.mkdirs(); writeText("invalid pdf") }
        val session = PdfSessionStore(isolated)
        assertTrue(session.save(legacySource(file, PdfCalibrationIdentity.contentKey(file)!!)))
        val originalRecord = sessionPrefs.getString("active_pdf", null)
        assertEquals(setOf(LegacyLibraryState.PDF_UNCONVERTIBLE), uncertain(reader().read("OSM_TOPO")))
        assertEquals(originalRecord, sessionPrefs.getString("active_pdf", null))
        assertTrue(file.isFile)
    }

    @Test
    fun aCalibrationLibraryThatWontOpenIsUncertainNotNamesNothing() {
        // it used to read as "names nothing" and got cleared with the empty library write
        assertTrue(sessionPrefs.edit().putString("pdf_calibrations", "AAAAbGVmdG92ZXI=").commit())
        assertEquals(setOf(LegacyLibraryState.SESSION_INVALID), uncertain(reader().read("OSM_TOPO")))
        assertTrue(sessionPrefs.contains("pdf_calibrations"))
    }

    @Test
    fun aSessionThatWontOpenSalvagesItsPdfWithTheRealStoresAndClearsNothing() {
        val file = pdf("pdf_maps/import-0123456789abcdef.pdf")
        assertTrue(sessionPrefs.edit().putString("active_pdf", "AAAAbm90IGEgc2VhbGVkIGJsb2I=").commit())
        val library = ImportedMapLibraryStore(root)
        val migrator = LegacyLibraryMigrator(
            filesDir = root,
            library = library,
            drafts = CalibrationDraftStore(root),
            legacy = reader(),
            defaultStyle = "OSM_TOPO",
            recoveredName = { "Recovered map $it" },
            inspectPdf = { f -> LibraryRebuild.withWatchdog { stop -> PdfInspector.inspect(isolated, f, stop) } },
            validateMbtiles = { f -> MBTilesStore.open(f.path)?.let { it.close(); true } ?: false },
        )
        assertEquals(LibraryLoad.Empty, library.load())
        assertTrue(migrator.isDue())
        val migrated = migrator.migrate()
        assertEquals(RestoreMigration.SALVAGE, (migrated as LegacyLibraryMigrator.Outcome.Written).migration)
        val plan = migrator.settle(library.load(), migrated)
        assertEquals(RestoreNotice.RECOVERED, plan.notice)
        assertFalse(plan.authoritative)
        val state = (library.load() as LibraryLoad.Loaded).state
        assertEquals(true, state.recoveryPreservesOrphans)
        val entry = state.entries.single()
        assertEquals("pdf_maps/import-0123456789abcdef.pdf", entry.fileName)
        assertEquals("Recovered map 1", entry.displayName)
        // inspected for real: one page, no georef, so it's waiting on a calibration
        assertEquals(1, entry.pdf!!.pageCount)
        assertEquals(EntryState.NEEDS_CALIBRATION, LibraryEntryRules.state(LibraryEntryRules.facts(entry), library.fileStatus(entry)))
        assertTrue(file.isFile)
        assertTrue("the old record got cleared", sessionPrefs.contains("active_pdf"))
    }

    @Test
    fun anAuthenticatedReadThatNamesNothingIsAbsentSoTheLibraryGetsWrittenEmpty() {
        // the old app's calibrated sheet is gone, its sealed calibrations still open fine
        val file = pdf("pdf_maps/legacy.pdf")
        val fids = (1..3).map { Fiduciary(pdfX = 100.0 * it, pdfY = 80.0 * it, mgrs = "", latitude = -34.0 + it * 0.01, longitude = 150.0 + it * 0.01) }
        val session = PdfSessionStore(isolated)
        assertTrue(session.save(legacySource(file, PdfCalibrationIdentity.contentKey(file)!!, Calibration.Fiduciaries(fids, georef))))
        assertTrue(sessionPrefs.contains("pdf_calibrations"))
        assertTrue(file.delete())
        val r = reader()
        assertTrue(r.hasLegacyState())
        val read = r.read("OSM_TOPO")
        assertEquals(LegacyMapReader.Read.Absent, read)
        assertEquals(
            RestoreMigration.WRITE_EMPTY_AND_CLEAR,
            LibraryRestoreRules.plan(LibraryLoad.Empty, LibraryRestoreRules.legacyState(read)).migration,
        )
        // what the migration does next: write empty, then clear, after which nothing's left to migrate
        assertTrue(ImportedMapLibraryStore(root).write(LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO")))
        // that write's sealed-only record went to this test's ledger, not the app's (tearDown checks the app's)
        assertTrue(ImportedMapLibraryStore.LABEL in ledger)
        assertTrue(r.clearAfterMigration())
        assertFalse(r.hasLegacyState())
        assertTrue(ImportedMapLibraryStore(root).load() is LibraryLoad.Loaded)
    }
}
