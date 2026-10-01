package com.tacmap.calibration

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * D3 / F5: the reconcile the app actually runs keeps the PDF's bake. Real sealed session
 * (keystore, so device only), real selection store on the app's own files dir, through
 * reconcileWithPdfSession, which is what MapViewModel calls. Everything the reconcile
 * could touch gets copied aside first and put back after, the emulator has real imports on it
 */
@RunWith(AndroidJUnit4::class)
class PdfBakeReconcileInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = context.filesDir
    private val sessions = PdfSessionStore(context)
    private lateinit var snapshot: PdfSessionSnapshot
    private val backup = File(context.cacheDir, "reconcile-backup-${System.nanoTime()}")
    private val roots = listOf("pdf_maps", "mbtiles", "offline_tiles")

    @Before
    fun setUp() {
        snapshot = sessions.snapshotActiveSession()
        backup.mkdirs()
        files.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(backup, it.name), overwrite = true) }
        for (r in roots) File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach {
            it.copyTo(File(backup, "$r/${it.name}"), overwrite = true)
        }
    }

    @After
    fun tearDown() {
        sessions.restoreActiveSession(snapshot)
        for (r in roots) {
            File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            File(backup, r).listFiles().orEmpty().forEach { it.copyTo(File(files, "$r/${it.name}"), overwrite = true) }
        }
        backup.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(files, it.name), overwrite = true) }
        backup.deleteRecursively()
    }

    /** a real one page 600 x 400 pt PDF, the session store opens it on restore */
    private fun blankPdf(): File {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val file = File(files, "pdf_maps/reconcile-${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs() }
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(600f, 400f)))
            doc.save(file)
        }
        return file
    }

    private fun pdfSource(file: File): PdfMapSource {
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
            displayName = "reconcile",
            kind = MapSourceKind.CALIBRATED_PDF,
            calibration = Calibration.Parsed(georef),
            geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
        )
    }

    @Test
    fun theAppsReconcileKeepsTheSessionsBakeAndReapsAStaleOne() {
        val pdf = blankPdf()
        val src = pdfSource(pdf)
        assertTrue(sessions.save(src))
        val token = src.render.renderGuardToken
        val georefJson = src.placement!!.canonicalJson()
        val bakeFile = File(files, "offline_tiles/tacmap-bake-${UUID.randomUUID()}.mbtiles").apply { parentFile!!.mkdirs(); writeText("tiles") }
        val stale = File(files, "offline_tiles/tacmap-bake-${UUID.randomUUID()}.mbtiles").apply { writeText("old georef") }
        val bake = PersistedPdfBake(bakeFile.name, "a".repeat(64), 0, 12, 256, bakeFile.length())
        assertEquals(PdfSessionStore.AttachResult.Attached, sessions.attachBake(pdf.name, token, georefJson, bake))
        assertEquals(bakeFile.canonicalPath, sessions.activeBakeFile()?.canonicalPath)

        val selection = ActiveMapSelectionStore(context)
        assertTrue(selection.saveActiveAndRetainedPdf(BasemapStyle.OSM_TOPO))
        assertTrue(selection.reconcileWithPdfSession(sessions))
        assertTrue("the PDF stays", pdf.isFile)
        assertTrue("the session's bake stays with its PDF", bakeFile.isFile)
        assertFalse("a bake the session doesn't name is reaped", stale.exists())

        // Remove Offline Tiles: the session drops it, the next reconcile takes the file
        assertTrue(sessions.clearBake(pdf.name, token))
        assertNull(sessions.activeBakeFile())
        assertTrue(selection.reconcileWithPdfSession(sessions))
        assertTrue(pdf.isFile)
        assertFalse(bakeFile.exists())
    }

    /**
     * R2-S2: Remove Offline Tiles while the stored PDF is missing. The reconcile won't run then
     * (keep set has a missing file), so the remove path deletes the bake + sidecars itself
     */
    @Test
    fun removeOfflineTilesDeletesTheBakeWhileThePdfIsMissing() {
        val pdf = blankPdf()
        // content bound like a real import, so the missing file keeps the session (OD-F4)
        val src = pdfSource(pdf).let { it.withRender(it.render.copy(contentKey = PdfStoredFile.contentKey(pdf))) }
        assertTrue(sessions.save(src))
        val token = src.render.renderGuardToken
        val bakeFile = File(files, "offline_tiles/tacmap-bake-${UUID.randomUUID()}.mbtiles").apply { parentFile!!.mkdirs(); writeText("tiles") }
        val sidecars = listOf("-journal", "-wal", "-shm").map { File(bakeFile.path + it).apply { writeText("x") } }
        val bake = PersistedPdfBake(bakeFile.name, "a".repeat(64), 0, 12, 256, bakeFile.length())
        assertEquals(PdfSessionStore.AttachResult.Attached, sessions.attachBake(pdf.name, token, src.placement!!.canonicalJson(), bake))
        val selection = ActiveMapSelectionStore(context)
        assertTrue(selection.saveActiveAndRetainedPdf(BasemapStyle.OSM_TOPO))

        assertTrue(pdf.delete())
        // the plain reconcile refuses with the PDF gone and leaves the tiles
        assertFalse(selection.reconcileWithPdfSession(sessions))
        assertTrue(bakeFile.isFile)

        // a stale token clears nothing and deletes nothing
        assertNull(sessions.removeBake(files, pdf.name, UUID.randomUUID().toString(), ActiveMapSelectionStore(context)))
        assertTrue(bakeFile.isFile)

        var letGo = false
        assertEquals(true, sessions.removeBake(files, pdf.name, token, ActiveMapSelectionStore(context)) { letGo = true })
        assertTrue(letGo)
        assertNull(sessions.activeBakeFile())
        assertFalse("bake deleted with the PDF missing", bakeFile.exists())
        sidecars.forEach { assertFalse("${it.name} deleted", it.exists()) }
    }

    @Test
    fun attachBakeSaysSourceChangedForAnotherPdfOrToken() {
        val pdf = blankPdf()
        val stored = pdfSource(pdf)
        assertTrue(sessions.save(stored))
        val json = stored.placement!!.canonicalJson()
        val bake = PersistedPdfBake("tacmap-bake-x.mbtiles", "a".repeat(64), 0, 12, 256, 1)
        // R6: a mismatch is sourceChanged, never writeFailed
        assertEquals(PdfSessionStore.AttachResult.SourceChanged, sessions.attachBake("other.pdf", stored.render.renderGuardToken, json, bake))
        assertEquals(PdfSessionStore.AttachResult.SourceChanged, sessions.attachBake(pdf.name, UUID.randomUUID().toString(), json, bake))
        assertEquals(PdfSessionStore.AttachResult.SourceChanged, sessions.attachBake(pdf.name, stored.render.renderGuardToken, "{}", bake))
        assertNull(sessions.activeBakeFile())
    }

    // ---- R3-2 bake-only sweep, real sealed session ----

    private fun plantBake(name: String = "tacmap-bake-${UUID.randomUUID()}.mbtiles"): List<File> {
        val tiles = File(files, "offline_tiles").apply { mkdirs() }
        return listOf("", "-journal", "-wal", "-shm").map { File(tiles, name + it).apply { writeText("x") } }
    }

    private fun sessionWithBake(): Triple<File, PdfMapSource, File> {
        val pdf = blankPdf()
        val src = pdfSource(pdf).let { it.withRender(it.render.copy(contentKey = PdfStoredFile.contentKey(pdf))) }
        assertTrue(sessions.save(src))
        val bakeFile = plantBake()[0]
        val bake = PersistedPdfBake(bakeFile.name, "a".repeat(64), 0, 12, 256, bakeFile.length())
        assertEquals(
            PdfSessionStore.AttachResult.Attached,
            sessions.attachBake(pdf.name, src.render.renderGuardToken, src.placement!!.canonicalJson(), bake),
        )
        return Triple(pdf, src, bakeFile)
    }

    @Test
    fun theBakeSweepKeepsTheNamedBakeAndTakesOrphansWithThePdfMissing() {
        val (pdf, _, named) = sessionWithBake()
        val orphan = plantBake()
        val pack = File(files, "offline_tiles/imported-${UUID.randomUUID()}.mbtiles").apply { writeText("pack") }
        assertTrue(pdf.delete())
        assertEquals(PdfBakeRecordRead.Read(named.name), sessions.readBakeRecord { false })
        assertEquals(true, sessions.sweepOrphanBakes(files, ActiveMapSelectionStore(context)))
        assertTrue("the session's bake stays", named.isFile)
        orphan.forEach { assertFalse("${it.name} still there", it.exists()) }
        assertTrue("not a bake, not ours to sweep", pack.isFile)
        // the record is untouched by the sweep
        assertEquals(named.canonicalPath, sessions.activeBakeFile()?.canonicalPath)
    }

    @Test
    fun removeOfflineTilesAlsoSweepsWhatAnEarlierFailedRemoveLeft() {
        val (pdf, src, named) = sessionWithBake()
        val leftover = plantBake()
        assertTrue(pdf.delete())
        assertEquals(true, sessions.removeBake(files, pdf.name, src.render.renderGuardToken, ActiveMapSelectionStore(context)))
        assertEquals(PdfBakeRecordRead.Read(null), sessions.readBakeRecord { false })
        assertFalse(named.exists())
        leftover.forEach { assertFalse("${it.name} still there", it.exists()) }
    }

    @Test
    fun noSessionRecordIsACleanReadAndEveryBakeGoes() {
        // only clean while the selector doesn't expect a PDF either
        assertTrue(ActiveMapSelectionStore(context).saveOnlineAndClearRetained(BasemapStyle.OSM_TOPO))
        assertTrue(sessions.clear())
        val orphan = plantBake()
        assertEquals(PdfBakeRecordRead.Read(null), sessions.readBakeRecord { false })
        assertEquals(true, sessions.sweepOrphanBakes(files, ActiveMapSelectionStore(context)))
        orphan.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun anUnreadableSessionRecordSkipsTheSweep() {
        val orphan = plantBake()
        // sealed-looking but won't open: what a locked or rotated keystore looks like from here
        assertTrue(
            context.getSharedPreferences("pdf_session", Context.MODE_PRIVATE).edit()
                .putString("active_pdf", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit(),
        )
        assertEquals(PdfBakeRecordRead.Unreadable, sessions.readBakeRecord { false })
        assertNull(sessions.sweepOrphanBakes(files, ActiveMapSelectionStore(context)))
        orphan.forEach { assertTrue("${it.name} deleted on a guess", it.isFile) }
    }

    @Test
    fun noSessionRecordWhileTheSelectorSaysPdfSkipsTheSweep() {
        // F6: a PDF selector with no record is a record we failed to see, not one naming nothing
        val selection = ActiveMapSelectionStore(context)
        assertTrue(selection.saveActiveAndRetainedPdf(BasemapStyle.OSM_TOPO))
        assertTrue(sessions.clear())
        val orphan = plantBake()
        assertEquals(PdfBakeRecordRead.Unreadable, sessions.readBakeRecord(selection::mayNamePdf))
        assertNull(sessions.sweepOrphanBakes(files, selection))
        orphan.forEach { assertTrue("${it.name} deleted on a guess", it.isFile) }

        // retained PDF alone counts too
        assertTrue(selection.saveOnlineAndClearRetained(BasemapStyle.OSM_TOPO))
        assertTrue(selection.saveRetainedPdf(BasemapStyle.OSM_TOPO))
        assertNull(sessions.sweepOrphanBakes(files, selection))
        orphan.forEach { assertTrue(it.isFile) }
    }

    @Test
    fun aBakeRecordWeWouldntHaveWrittenNamesNothing() {
        val pdf = blankPdf()
        val src = pdfSource(pdf)
        assertTrue(sessions.save(src))
        val bakeFile = plantBake()[0]
        // not a 64 hex key: never written by a real bake, same rule as iOS validBake
        val bad = PersistedPdfBake(bakeFile.name, "k", 0, 12, 256, bakeFile.length())
        assertEquals(PdfSessionStore.AttachResult.Attached, sessions.attachBake(pdf.name, src.render.renderGuardToken, src.placement!!.canonicalJson(), bad))
        assertEquals(PdfBakeRecordRead.Read(null), sessions.readBakeRecord { false })
        assertNull("not in the reconcile keep set either", sessions.activeBakeFile())
        assertNull("and a restore doesn't read it", (sessions.loadSession() as? PdfSessionLoad.Ready)?.source?.render?.bake)
        assertEquals(true, sessions.sweepOrphanBakes(files, ActiveMapSelectionStore(context)))
        assertFalse(bakeFile.exists())
    }
}
