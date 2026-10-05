package com.tacmap.map

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.ActiveRef
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.CalibrationDraftStore
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.ImportedMapLibraryStore
import com.tacmap.calibration.LibraryLoad
import com.tacmap.calibration.LibraryState
import com.tacmap.calibration.ManualCalibration
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfBakeManager
import com.tacmap.calibration.PdfBaker
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfEntryInfo
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfGeoreferenceCodec
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.calibration.PdfStoredFile
import com.tacmap.calibration.PlaneAffine
import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.CalibrationTarget
import com.tacmap.calibration.fiducial.StoredPagePoint
import com.tacmap.map.render.pdf.PdfRenderExecutor
import com.tacmap.map.render.pdf.PdfRenderSessions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.tacmap.util.MissionKeyUnlockRule
import java.io.File
import java.util.UUID

/**
 * A1 (3.0.1): two MapViewModels on the one sealed library. That's what a second MainActivity
 * (the Unit Sync notification used to stack one) or a bake outliving its screen gives you.
 * Whatever the stale one does, it must never shrink the library, put back an old calibration
 * or reconcile away a file the other one added. Real view models on the app's own files dir,
 * so everything they could touch is copied aside first and put back after
 */
@RunWith(AndroidJUnit4::class)
class LibraryStaleWriterInstrumentedTest {
    @get:Rule val missionKey = MissionKeyUnlockRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as Application
    private val files = context.filesDir
    private val library = ImportedMapLibraryStore(files)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backup = File(context.cacheDir, "stalewriter-backup-${System.nanoTime()}")
    private val roots = listOf("pdf_maps", "mbtiles", "offline_tiles", PdfBaker.WORK_DIR)
    private val stores = mutableListOf<ViewModelStore>()

    private val georef = PdfGeoreference(
        page = 0,
        crs = GeoCrs.Geographic,
        datum = GeoDatums.WGS84,
        affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
        crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
        origin = GeorefOrigin.ADOBE_VP,
    )
    private val box = listOf(listOf(0.0, 0.0), listOf(600.0, 0.0), listOf(600.0, 400.0), listOf(0.0, 400.0))

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
        // drafts from whatever ran before would only get in the way (auto resume)
        File(files, CalibrationDraftStore.FILE_NAME).delete()
    }

    @After
    fun tearDown() {
        (app as com.tacmap.app.TacticalApp).pdfBakeManager.cancel()
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        val lib = ImportedMapLibraryStore.FILE_NAME
        File(files, CalibrationDraftStore.FILE_NAME).delete()
        files.listFiles().orEmpty().filter { it.name.startsWith("$lib.corrupt-") && !File(backup, it.name).exists() }.forEach { it.delete() }
        for (r in roots) {
            File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            File(backup, r).listFiles().orEmpty().forEach { it.copyTo(File(files, "$r/${it.name}"), overwrite = true) }
        }
        backup.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(files, it.name), overwrite = true) }
        // none before this ran: leave an empty one. a deleted library reads as corrupt from
        // then on (WP4 r1 S1) and every activity test after this one would trip over it
        if (!File(backup, lib).exists()) {
            library.write(LibraryState(active = ActiveRef.online(BasemapStyle.OSM_TOPO.name), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name))
        }
        backup.deleteRecursively()
    }

    /** a small inked sheet, [lines] changes the bytes so every map gets its own contentKey */
    private fun inkedPdf(lines: Int): File {
        val file = File(files, "pdf_maps/stalewriter-${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(600f, 400f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { ink ->
                ink.setStrokingColor(0.1f, 0.2f, 0.9f)
                ink.setLineWidth(1.5f)
                for (k in 0..lines) {
                    ink.moveTo(k * 600f / lines, 0f); ink.lineTo(k * 600f / lines, 400f)
                    ink.moveTo(0f, k * 400f / lines); ink.lineTo(600f, k * 400f / lines)
                }
                ink.stroke()
            }
            doc.save(file)
        }
        return file
    }

    /** a GeoPDF entry the way an import leaves it, file and all */
    private fun pdfEntry(lines: Int): ImportedMapEntry {
        val pdf = inkedPdf(lines)
        return ImportedMapEntry(
            id = UUID.randomUUID().toString(),
            kind = "pdf",
            fileName = "pdf_maps/${pdf.name}",
            displayName = pdf.nameWithoutExtension,
            contentKey = PdfStoredFile.contentKey(pdf),
            byteCount = pdf.length(),
            fileModifiedAtMs = pdf.lastModified(),
            importedAtMs = System.currentTimeMillis(),
            pdf = PdfEntryInfo(
                pageCount = 1, pageIndex = 0, rotate = 0, pageBox = box,
                embedded = PdfGeoreferenceCodec.encode(georef),
                geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
                renderGuardToken = UUID.randomUUID().toString(),
            ),
        )
    }

    private fun seed(active: ImportedMapEntry, vararg entries: ImportedMapEntry) {
        val state = LibraryState(
            active = ActiveRef.entry(active.id),
            preferredOnlineStyle = BasemapStyle.OSM_TOPO.name,
            entries = entries.toList(),
        )
        assertTrue(library.write(state))
    }

    private fun loaded(): LibraryState =
        requireNotNull((library.load() as? LibraryLoad.Loaded)?.state) { "the sealed library doesn't load" }

    private fun viewModel(store: ViewModelStore = ViewModelStore()): MapViewModel {
        stores += store
        var vm: MapViewModel? = null
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MapViewModel::class.java]
        }
        return vm!!
    }

    private fun onMain(block: () -> Boolean): Boolean {
        var ok = false
        instrumentation.runOnMainSync { ok = block() }
        return ok
    }

    private fun waitUntil(ms: Long = 60_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(25)
        }
    }

    private fun workFiles() = PdfBaker.workDir(context).listFiles().orEmpty().toList()

    /** Generate Offline Tiles on whatever [vm] shows, the biggest option or the smallest. returns once it's really writing */
    private fun startBake(vm: MapViewModel, biggest: Boolean): PdfBakeManager {
        val manager = vm.bakeManager
        instrumentation.runOnMainSync { manager.prepare(vm.mapSource.value as PdfMapSource, 1f) }
        waitUntil(what = "bake estimate") {
            manager.state.value is PdfBakeManager.State.Confirming || manager.state.value is PdfBakeManager.State.Failed
        }
        val proposal = (manager.state.value as PdfBakeManager.State.Confirming).proposal
        val fits = proposal.options.filter { it.enoughSpace }
        val choice = (if (biggest) fits.maxByOrNull { it.option.maxZoom } else fits.minByOrNull { it.option.maxZoom }) ?: proposal.initial
        instrumentation.runOnMainSync { manager.start(choice) }
        waitUntil(what = "bake partial") { workFiles().any { it.name.endsWith(".partial") } }
        return manager
    }

    private fun point(n: Int) = CalibrationPoint(
        "p$n", n, StoredPagePoint(100.0 * n, 50.0 * n), "x", CalibrationReference.Geographic(-34.0 + n * 0.01, 150.0 + n * 0.01),
    )

    @Test
    fun aStaleViewModelsBasemapPickKeepsTheMapAndDraftTheOtherOneAdded() {
        val p = pdfEntry(lines = 8)
        seed(p, p)
        val a = viewModel()
        val b = viewModel()
        // B imports M and starts calibrating it (a parked draft)
        val m = pdfEntry(lines = 11)
        assertTrue(onMain { b.addImportedEntry(m, activate = true) })
        val draft = CalibrationDraft(
            contentKey = m.contentKey!!, pageIndex = 0, entryId = m.id, datumId = "WGS84",
            points = listOf(point(1)), nextNumber = 2, active = false, updatedAtMs = 1,
        )
        assertTrue(CalibrationDraftStore(files).save(draft))

        // back in A, still holding the library from before M: pick another basemap
        assertTrue(onMain { a.selectBaseMap(BasemapStyle.OSM_STREET) })

        val durable = loaded()
        assertEquals("nothing dropped", setOf(p.id, m.id), durable.entries.map { it.id }.toSet())
        assertEquals("and A's pick still landed", ActiveRef.online(BasemapStyle.OSM_STREET.name), durable.active)
        assertTrue("M's PDF reconciled away: ${File(files, "pdf_maps").list()?.toList()}", File(files, m.fileName).isFile)
        assertNotNull("M's draft pruned", CalibrationDraftStore(files).load(draft.key))
        assertNotNull("A holds the sealed library now", a.libraryState.value?.entry(m.id))
    }

    @Test
    fun aStaleViewModelNeverPutsBackACalibrationTheOtherOneReplaced() {
        val p = pdfEntry(lines = 8)
        val q = pdfEntry(lines = 13)
        seed(p, p, q)
        val a = viewModel()
        val b = viewModel()
        val manual = ManualCalibration(
            datumId = "WGS84",
            points = (1..4).map(::point),
            georef = PdfGeoreferenceCodec.encode(georef.copy(affine = PlaneAffine(0.5 / 600.0, 0.0, 150.001, 0.0, 0.5 / 400.0, -34.0))),
            n = 4, rmsM = 1.0, grade = "good", savedAtMs = 5,
        )
        val target = CalibrationTarget(p.id, p.contentKey!!, 0, p.pdf!!.pageBoxPoints, 0)
        assertTrue(onMain { b.commitCalibration(target, manual) })

        // A never saw that calibration, opening the other map must not put P back the way it was
        assertTrue(onMain { a.activateImportedMap(q.id) })

        val durable = loaded()
        assertEquals("B's calibration reverted", manual, durable.entry(p.id)?.pdf?.manual)
        assertEquals(ActiveRef.entry(q.id), durable.active)
    }

    @Test
    fun aViewModelComingBackToTheFrontTakesWhatTheOtherOneWrote() {
        val p = pdfEntry(lines = 8)
        seed(p, p)
        val a = viewModel()
        val b = viewModel()
        val m = pdfEntry(lines = 11)
        assertTrue(onMain { b.addImportedEntry(m, activate = false) })

        // MapScreen composing again is A's resume
        instrumentation.runOnMainSync { a.onMissionDataUnlocked() }

        assertNotNull("A still on the old library after it came back", a.libraryState.value?.entry(m.id))
    }

    @Test
    fun aBakeWhoseViewModelWasClearedRecordsThroughTheLiveOneAndKeepsItsImport() {
        val p = pdfEntry(lines = 8)
        seed(p, p)
        val first = ViewModelStore()
        val v1 = viewModel(first)
        val manager = startBake(v1, biggest = true)

        // the screen that started it goes (activity finished, the app scoped bake keeps going)
        instrumentation.runOnMainSync { first.clear() }
        val v2 = viewModel()
        val m = pdfEntry(lines = 11)
        assertTrue(onMain { v2.addImportedEntry(m, activate = false) })
        assertTrue("bake already done, nothing raced: ${manager.state.value}", manager.state.value is PdfBakeManager.State.Running)

        waitUntil(ms = 240_000, what = "bake to land") { manager.state.value !is PdfBakeManager.State.Running }
        assertEquals(PdfBakeManager.State.Idle, manager.state.value)
        val durable = loaded()
        assertNotNull("the import made while the bake ran is gone", durable.entry(m.id))
        assertTrue("M's PDF reconciled away", File(files, m.fileName).isFile)
        val bake = durable.entry(p.id)?.pdf?.bake
        assertNotNull("the bake wasn't recorded", bake)
        assertTrue(File(files, "offline_tiles/${bake!!.fileName}").isFile)
    }

    @Test
    fun closingTheNewerViewModelLeavesTheOlderOneRecordingBakes() {
        val p = pdfEntry(lines = 8)
        seed(p, p)
        val v1 = viewModel()
        // a second screen comes up and goes again (Back out of a second MainActivity)
        val second = ViewModelStore()
        viewModel(second)
        instrumentation.runOnMainSync { second.clear() }

        val manager = startBake(v1, biggest = false)
        waitUntil(ms = 240_000, what = "bake to land") { manager.state.value !is PdfBakeManager.State.Running }

        assertEquals("nobody recorded it", PdfBakeManager.State.Idle, manager.state.value)
        assertNotNull(loaded().entry(p.id)?.pdf?.bake)
    }
}
