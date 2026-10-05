package com.tacmap.map

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.ActiveMapSelectionStore
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfBakeManager
import com.tacmap.calibration.PdfBakeRecordRead
import com.tacmap.calibration.PdfBaker
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.calibration.PdfSessionSnapshot
import com.tacmap.calibration.PdfSessionStore
import com.tacmap.calibration.PdfStoredFile
import com.tacmap.calibration.PersistedPdfBake
import com.tacmap.calibration.PlaneAffine
import com.tacmap.calibration.canonicalJson
import com.tacmap.map.render.pdf.PdfRenderExecutor
import com.tacmap.map.render.pdf.PdfRenderSessions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.tacmap.util.MissionKeyUnlockRule
import java.io.File
import java.util.UUID

/**
 * OD3-R3-1, the Android side, on the WP4 library since the merge: Layers > Imported maps >
 * Delete is MapViewModel.deleteImportedMap (the row's confirm calls exactly that). With or
 * without a bake running it has to leave nothing for that map in pdf_maps, offline_tiles or
 * pdf_bake_work and no library entry. Also R3-4: Remove Offline Tiles hands back on main
 * straight away and the source loses its bake once the IO half is done. Real view model on
 * the app's own files dir, so everything it could touch is copied aside first and put back
 * after (the emulator has real imports on it)
 */
@RunWith(AndroidJUnit4::class)
class PdfDeleteMapInstrumentedTest {
    @get:Rule val missionKey = MissionKeyUnlockRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as Application
    private val files = context.filesDir
    private val library = com.tacmap.calibration.ImportedMapLibraryStore(files)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backup = File(context.cacheDir, "deletemap-backup-${System.nanoTime()}")
    private val roots = listOf("pdf_maps", "mbtiles", "offline_tiles", PdfBaker.WORK_DIR)
    private val vmStore = ViewModelStore()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        PdfRenderSessions.init(context)
        PdfRenderExecutor.foreground = true
        backup.mkdirs()
        files.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(backup, it.name), overwrite = true) }
        for (r in roots) File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach {
            it.copyTo(File(backup, "$r/${it.name}"), overwrite = true)
        }
    }

    @After
    fun tearDown() {
        (app as com.tacmap.app.TacticalApp).pdfBakeManager.cancel()
        instrumentation.runOnMainSync { vmStore.clear() }
        // the library file is top level, the copy below puts the user's one back. its sealed-only
        // marker (and any quarantine) go too, or a library that wasn't there before the test
        // reads as vanished-after-written = corrupt from now on (WP4 r1 S1)
        val lib = com.tacmap.calibration.ImportedMapLibraryStore.FILE_NAME
        File(files, lib).delete()
        File(files, ".$lib.sealed-only-v1").delete()
        files.listFiles().orEmpty().filter { it.name.startsWith("$lib.corrupt-") && !File(backup, it.name).exists() }.forEach { it.delete() }
        for (r in roots) {
            File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            File(backup, r).listFiles().orEmpty().forEach { it.copyTo(File(files, "$r/${it.name}"), overwrite = true) }
        }
        backup.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(files, it.name), overwrite = true) }
        backup.deleteRecursively()
    }

    private fun inkedPdf(): File {
        val file = File(files, "pdf_maps/deletemap-${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(600f, 400f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { ink ->
                ink.setStrokingColor(0.9f, 0.1f, 0.1f)
                ink.setLineWidth(1.5f)
                for (k in 0..12) {
                    ink.moveTo(k * 50f, 0f); ink.lineTo(k * 50f, 400f)
                    ink.moveTo(0f, k * 33f); ink.lineTo(600f, k * 33f)
                }
                ink.stroke()
            }
            doc.save(file)
        }
        return file
    }

    private val georef = PdfGeoreference(
        page = 0,
        crs = GeoCrs.Geographic,
        datum = GeoDatums.WGS84,
        affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
        crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
        origin = GeorefOrigin.ADOBE_VP,
    )

    private fun bakeFiles() = File(files, "offline_tiles").listFiles().orEmpty().filter { it.name.startsWith("tacmap-bake-") }
    private fun workFiles() = PdfBaker.workDir(context).listFiles().orEmpty().toList()

    /** the PDF as the active library entry with a published bake on it, what a real import + bake leaves */
    private fun storedPdfWithBake(): Pair<File, com.tacmap.calibration.ImportedMapEntry> {
        val pdf = inkedPdf()
        val bakeFile = File(files, "offline_tiles/tacmap-bake-${UUID.randomUUID()}.mbtiles").apply { parentFile!!.mkdirs(); writeText("tiles") }
        val bake = PersistedPdfBake(bakeFile.name, "a".repeat(64), 0, 12, 256, bakeFile.length())
        val box = listOf(listOf(0.0, 0.0), listOf(600.0, 0.0), listOf(600.0, 400.0), listOf(0.0, 400.0))
        val entry = com.tacmap.calibration.ImportedMapEntry(
            id = UUID.randomUUID().toString(),
            kind = "pdf",
            fileName = "pdf_maps/${pdf.name}",
            displayName = pdf.nameWithoutExtension,
            // content bound like a real import
            contentKey = PdfStoredFile.contentKey(pdf),
            byteCount = pdf.length(),
            fileModifiedAtMs = pdf.lastModified(),
            importedAtMs = System.currentTimeMillis(),
            pdf = com.tacmap.calibration.PdfEntryInfo(
                pageCount = 1, pageIndex = 0, rotate = 0, pageBox = box,
                embedded = com.tacmap.calibration.PdfGeoreferenceCodec.encode(georef),
                geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
                bake = bake,
                renderGuardToken = UUID.randomUUID().toString(),
            ),
        )
        val state = com.tacmap.calibration.LibraryState(
            active = com.tacmap.calibration.ActiveRef.entry(entry.id),
            preferredOnlineStyle = BasemapStyle.OSM_TOPO.name,
            entries = listOf(entry),
        )
        assertTrue(library.write(state))
        return pdf to entry
    }

    private fun storedEntry(id: String): com.tacmap.calibration.ImportedMapEntry? =
        (library.load() as? com.tacmap.calibration.LibraryLoad.Loaded)?.state?.entry(id)

    private fun viewModel(): MapViewModel {
        var vm: MapViewModel? = null
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(vmStore, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MapViewModel::class.java]
        }
        return vm!!
    }

    private fun waitUntil(ms: Long = 20_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(25)
        }
    }

    private fun assertNothingLeftFor(pdf: File) {
        assertFalse("the PDF is gone", pdf.exists())
        assertTrue("pdf_maps: ${File(files, "pdf_maps").list()?.toList()}", File(files, "pdf_maps").listFiles().orEmpty().none { it.name == pdf.name })
        assertEquals("offline_tiles bakes", emptyList<String>(), bakeFiles().map { it.name })
        assertEquals("pdf_bake_work", emptyList<String>(), workFiles().map { it.name })
        val left = (library.load() as? com.tacmap.calibration.LibraryLoad.Loaded)?.state
        assertTrue("the library still loads", left != null)
        assertTrue("no library entry: ${left!!.entries}", left.entries.none { it.fileName == "pdf_maps/${pdf.name}" })
    }

    @Test
    fun deleteMapLeavesNothingBehindWithNoBakeRunning() {
        val (pdf, entry) = storedPdfWithBake()
        val vm = viewModel()
        assertEquals(pdf.name, (vm.mapSource.value as? PdfMapSource)?.uri?.path?.let(::File)?.name)
        var ok = false
        instrumentation.runOnMainSync { ok = vm.deleteImportedMap(entry.id) }
        assertTrue(ok)
        assertNothingLeftFor(pdf)
        assertTrue("back on the online map", vm.mapSource.value is com.tacmap.calibration.OnlineRasterMapSourceAndroid)
    }

    @Test
    fun deleteMapDuringARunningBakeLeavesNothingBehind() {
        val (pdf, entry) = storedPdfWithBake()
        val vm = viewModel()
        val manager = vm.bakeManager
        instrumentation.runOnMainSync { manager.prepare(vm.mapSource.value as PdfMapSource, 1f) }
        waitUntil(what = "bake estimate") { manager.state.value is PdfBakeManager.State.Confirming || manager.state.value is PdfBakeManager.State.Failed }
        val proposal = (manager.state.value as PdfBakeManager.State.Confirming).proposal
        val choice = proposal.options.filter { it.enoughSpace }.maxByOrNull { it.option.maxZoom } ?: proposal.initial
        instrumentation.runOnMainSync { manager.start(choice) }
        // really running: the .partial is there and it's on the PDF's token
        waitUntil(what = "bake partial") { workFiles().any { it.name.endsWith(".partial") } }
        assertEquals(entry.renderGuardToken, (manager.state.value as? PdfBakeManager.State.Running)?.token)

        var ok = false
        instrumentation.runOnMainSync { ok = vm.deleteImportedMap(entry.id) }
        assertTrue(ok)
        assertEquals(PdfBakeManager.State.Idle, manager.state.value)
        // the cancelled run unwinds on its own, its partial goes on the way out
        waitUntil(what = "bake unwound: ${workFiles()}") { workFiles().isEmpty() }
        // and a publish that was already under way can't land a file for a map that's gone
        Thread.sleep(1_500)
        assertNothingLeftFor(pdf)
    }

    @Test
    fun removeOfflineTilesReturnsAtOnceAndDropsTheBakeOffMain() {
        val (pdf, entry) = storedPdfWithBake()
        val vm = viewModel()
        val named = bakeFiles().single()
        assertTrue((vm.mapSource.value as PdfMapSource).render.bake != null)
        val orphan = File(files, "offline_tiles/tacmap-bake-${UUID.randomUUID()}.mbtiles").apply { writeText("left by a failed Remove") }
        // F2: the delete / sweep half is on IO, the reconcile after it comes back to main
        val reconcileOnMain = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        vm.onReconcileForTests = { reconcileOnMain += android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }
        var started = false
        instrumentation.runOnMainSync { started = vm.removePdfBake() }
        assertTrue(started)
        waitUntil(what = "source lost its bake") { (vm.mapSource.value as? PdfMapSource)?.render?.bake == null }
        waitUntil(what = "bake files gone") { !named.exists() && !orphan.exists() }
        assertNull("the entry's record is gone", storedEntry(entry.id)?.pdf?.bake)
        waitUntil(what = "reconcile after Remove") { reconcileOnMain.isNotEmpty() }
        assertEquals("every reconcile on main", listOf(true), reconcileOnMain.distinct())
        assertTrue("the PDF stays", pdf.isFile)
        assertEquals(pdf.name, (vm.mapSource.value as? PdfMapSource)?.uri?.path?.let(::File)?.name)
    }
}
