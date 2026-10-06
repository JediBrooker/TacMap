package com.tacmap.map

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.app.TacticalApp
import android.os.Looper
import com.tacmap.calibration.ActiveRef
import com.tacmap.calibration.AdmittedMbtiles
import com.tacmap.calibration.MBTilesStore
import com.tacmap.calibration.MbtilesPlaceholderSource
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.CalibrationDraftStore
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.ImportedMapLibraryStore
import com.tacmap.calibration.LibraryCommit
import com.tacmap.calibration.LibraryLoad
import com.tacmap.calibration.LibraryState
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfCalibrationIdentity
import com.tacmap.calibration.PdfEntryInfo
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tacmap.map.render.MbtilesLaunchDecision
import com.tacmap.map.render.MbtilesOpenGuard
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.pdf.PdfRenderExecutor
import com.tacmap.util.DataKey
import com.tacmap.util.MissionKeyUnlockRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * s14.1 open guard wiring: real view models restoring a real MBTiles pack from the sealed
 * library. A "relaunch" is a fresh MbtilesOpenGuard on the same file put in the app, which is
 * all a new process would have. The app's files are copied aside first and put back after
 */
@RunWith(AndroidJUnit4::class)
class MbtilesOpenGuardInstrumentedTest {
    @get:Rule val missionKey = MissionKeyUnlockRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as TacticalApp
    private val files = context.filesDir
    private val library = ImportedMapLibraryStore(files)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backup = File(context.cacheDir, "mbtiles-guard-backup-${System.nanoTime()}")
    private val roots = listOf("pdf_maps", "mbtiles", "offline_tiles")
    private val stores = mutableListOf<ViewModelStore>()
    private val guardFile = File(context.cacheDir, "mbtiles-guard-${System.nanoTime()}.json")
    private lateinit var appGuard: MbtilesOpenGuard
    private var wasForeground = true
    private val defaultAdmission = admitMbtilesPack
    private val gates = mutableListOf<CountDownLatch>()

    /** what the off main admission saw: was it on main, what was armed then, what it opened */
    private class Admission(val path: String, val onMain: Boolean, val armedAtOpen: List<String>, val source: OfflineTileMapSourceAndroid?)

    /** records every admission, the ones [hold] names wait on their gate first */
    private fun recordAdmissions(guard: () -> MbtilesOpenGuard, hold: Map<String, CountDownLatch> = emptyMap()): MutableList<Admission> {
        gates += hold.values
        val seen = Collections.synchronizedList(mutableListOf<Admission>())
        admitMbtilesPack = { path, name ->
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            val armedNow = armed(guard())
            hold.entries.firstOrNull { path.endsWith(it.key) }?.value?.await(5, TimeUnit.SECONDS)
            OfflineTileMapSourceAndroid.open(path, name).also { seen += Admission(path, onMain, armedNow, it) }
        }
        return seen
    }

    @Before
    fun setUp() {
        appGuard = app.mbtilesOpenGuard
        AdmittedMbtiles.clearForTesting()
        wasForeground = PdfRenderExecutor.foreground
        PdfRenderExecutor.foreground = true
        backup.mkdirs()
        files.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(backup, it.name), overwrite = true) }
        for (r in roots) File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach {
            it.copyTo(File(backup, "$r/${it.name}"), overwrite = true)
        }
        // a draft from whatever ran before would only get in the way (auto resume)
        File(files, CalibrationDraftStore.FILE_NAME).delete()
    }

    @After
    fun tearDown() {
        gates.forEach { it.countDown() }
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        admitMbtilesPack = defaultAdmission
        app.mbtilesOpenGuard = appGuard
        PdfRenderExecutor.foreground = wasForeground
        guardFile.delete()
        val lib = ImportedMapLibraryStore.FILE_NAME
        File(files, CalibrationDraftStore.FILE_NAME).delete()
        files.listFiles().orEmpty().filter { it.name.startsWith("$lib.corrupt-") && !File(backup, it.name).exists() }.forEach { it.delete() }
        for (r in roots) {
            File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            File(backup, r).listFiles().orEmpty().forEach { it.copyTo(File(files, "$r/${it.name}"), overwrite = true) }
        }
        backup.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(files, it.name), overwrite = true) }
        // none before: leave an empty one, a deleted library reads as corrupt from then on (S1)
        if (!File(backup, lib).exists()) {
            library.write(LibraryState(active = ActiveRef.online(BasemapStyle.OSM_TOPO.name), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name))
        }
        backup.deleteRecursively()
    }

    /** a small valid pack in the managed dir and its entry, the way an import leaves them */
    private fun packEntry(): ImportedMapEntry {
        val file = File(files, "mbtiles/import-guard-${UUID.randomUUID()}.mbtiles").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name text, value text)")
            db.execSQL("INSERT INTO metadata VALUES ('name', 'Guarded pack'), ('format', 'png'), ('minzoom', '8'), " +
                "('maxzoom', '8'), ('bounds', '150,-34,151,-33')")
            db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
            db.execSQL("INSERT INTO tiles VALUES (8, 234, 152, X'010203')")
        }
        return ImportedMapEntry(
            id = UUID.randomUUID().toString(),
            kind = "mbtiles",
            fileName = "mbtiles/${file.name}",
            displayName = "Guarded pack",
            contentKey = PdfCalibrationIdentity.contentKey(file),
            byteCount = file.length(),
            fileModifiedAtMs = file.lastModified(),
            importedAtMs = System.currentTimeMillis(),
        )
    }

    /** a one page PDF with no georef, so calibrating it is a preview */
    private fun sheetEntry(): ImportedMapEntry {
        PDFBoxResourceLoader.init(context)
        val file = File(files, "pdf_maps/guard-sheet-${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(600f, 400f)))
            doc.save(file)
        }
        return ImportedMapEntry(
            id = UUID.randomUUID().toString(),
            kind = "pdf",
            fileName = "pdf_maps/${file.name}",
            displayName = "Plain sheet",
            contentKey = PdfCalibrationIdentity.contentKey(file),
            byteCount = file.length(),
            fileModifiedAtMs = file.lastModified(),
            importedAtMs = System.currentTimeMillis(),
            pdf = PdfEntryInfo(
                pageCount = 1, pageIndex = 0, rotate = 0,
                pageBox = listOf(listOf(0.0, 0.0), listOf(600.0, 0.0), listOf(600.0, 400.0), listOf(0.0, 400.0)),
                geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
                renderGuardToken = UUID.randomUUID().toString(),
            ),
        )
    }

    private fun seed(active: ImportedMapEntry) {
        assertTrue(library.write(LibraryState(
            active = ActiveRef.entry(active.id),
            preferredOnlineStyle = BasemapStyle.OSM_TOPO.name,
            entries = listOf(active),
        )))
    }

    /** a new process as far as the guard can tell: a fresh instance over the file */
    private fun relaunch(): MbtilesOpenGuard = MbtilesOpenGuard(guardFile).also { app.mbtilesOpenGuard = it }

    private fun viewModel(): MapViewModel {
        val store = ViewModelStore().also { stores += it }
        var vm: MapViewModel? = null
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app as Application))[MapViewModel::class.java]
        }
        return vm!!
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(ms: Long = 10_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(25)
        }
    }

    private fun armed(g: MbtilesOpenGuard): List<String> = g.snapshot()["inProgress"]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun suspect(g: MbtilesOpenGuard): String? = g.snapshot()["suspect"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun activeId(): String? = (library.load() as LibraryLoad.Loaded).state.active.entryId

    @Test
    fun aPackThatDiedWhileOpeningIsHeldBackUntilOpenAnywayAndACleanDrawClearsIt() {
        val pack = packEntry()
        seed(pack)
        // last run armed the restore and the process died before the first draw settled
        assertTrue(MbtilesOpenGuard(guardFile).arm(pack.id, foreground = true))

        val guard = relaunch()
        val vm = viewModel()
        // held back: never opened, online in memory, the PDF guard's alert asks about the pack
        assertTrue(vm.mapSource.value is OnlineRasterMapSourceAndroid)
        assertEquals(CrashSuspect.Mbtiles(pack.id, "Guarded pack"), vm.pdfRecovery.value)
        assertEquals("durable selection untouched", pack.id, activeId())
        assertEquals(pack.id, suspect(guard))
        assertEquals(emptyList<String>(), armed(guard))

        // a later restore in the same process (a second screen) holds it back too
        val second = viewModel()
        assertTrue(second.mapSource.value is OnlineRasterMapSourceAndroid)
        assertEquals(pack.id, second.pdfRecovery.value?.entryId)

        // Not Now asks again next launch
        onMain { second.dismissPdfRecovery() }
        assertEquals(pack.id, suspect(guard))

        // Open Anyway: resolved, then the normal guarded open, armed until its first draw settles
        onMain { vm.openSuspectPdfAnyway() }
        assertNull(vm.pdfRecovery.value)
        assertNull(suspect(guard))
        assertEquals(listOf(pack.id), armed(guard))
        waitUntil(what = "opened anyway") { vm.mapSource.value is OfflineTileMapSourceAndroid }
        val shown = vm.mapSource.value as OfflineTileMapSourceAndroid
        // the map view's first read comes back, then it's quiet
        runBlocking { shown.renderTileSource().loadTile(TileIndex(8, 234, 103)) }
        waitUntil(what = "first draw settled") { armed(guard).isEmpty() }

        // next launch restores it like any other map
        val third = relaunch()
        assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(guardFile).launchDecision(pack.id))
        AdmittedMbtiles.clearForTesting()
        val vm3 = viewModel()
        assertNull(vm3.pdfRecovery.value)
        // armed for this restore too, every time, and done once nothing's asked for
        assertEquals(listOf(pack.id), armed(third))
        waitUntil(what = "restored") { vm3.mapSource.value is OfflineTileMapSourceAndroid }
        waitUntil(what = "no read settle") { armed(third).isEmpty() }
    }

    @Test
    fun deleteMapOnTheHeldBackPackClearsTheSuspectAndPickingItInLayersOpensIt() {
        val pack = packEntry()
        val other = packEntry()
        assertTrue(library.write(LibraryState(
            active = ActiveRef.entry(pack.id), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name, entries = listOf(pack, other),
        )))
        MbtilesOpenGuard(guardFile).arm(pack.id, foreground = true)
        val guard = relaunch()
        val vm = viewModel()
        assertEquals(pack.id, vm.pdfRecovery.value?.entryId)
        // picking another pack leaves the dialog and the suspect alone
        var ok = false
        onMain { ok = vm.activateImportedMap(other.id) }
        assertTrue(ok)
        assertEquals(pack.id, suspect(guard))
        assertEquals(listOf(other.id), armed(guard))
        // picking the suspect itself is Open Anyway
        onMain { ok = vm.activateImportedMap(pack.id) }
        assertTrue(ok)
        assertNull(suspect(guard))
        assertNull(vm.pdfRecovery.value)
        waitUntil(what = "picked pack up") { vm.shownEntryId() == pack.id && activeId() == pack.id }
        assertTrue(vm.mapSource.value is OfflineTileMapSourceAndroid)
        // that screen goes (its source with it, which is the end of the window too)
        onMain { stores.forEach { it.clear() } }
        stores.clear()
        waitUntil(what = "markers gone") { armed(guard).isEmpty() }

        // and Delete Map on a held back one resolves it deleted
        MbtilesOpenGuard(guardFile).arm(pack.id, foreground = true)
        val again = relaunch()
        val vm2 = viewModel()
        assertEquals(pack.id, vm2.pdfRecovery.value?.entryId)
        onMain { ok = vm2.deleteSuspectPdf() }
        assertTrue(ok)
        assertNull(suspect(again))
        assertNull(vm2.pdfRecovery.value)
        assertNull((library.load() as LibraryLoad.Loaded).state.entry(pack.id))
    }

    @Test
    fun pickingTheHeldBackPackInLayersAfterNotNowStillClearsTheSuspect() {
        val pack = packEntry()
        seed(pack)
        MbtilesOpenGuard(guardFile).arm(pack.id, foreground = true)
        val guard = relaunch()
        val vm = viewModel()
        assertEquals(pack.id, vm.pdfRecovery.value?.entryId)
        // Not Now: the alert goes, the suspect stays on disk
        onMain { vm.dismissPdfRecovery() }
        assertNull(vm.pdfRecovery.value)
        assertEquals(pack.id, suspect(guard))

        // then the user picks it in Layers anyway, that's an explicit open
        var ok = false
        onMain { ok = vm.activateImportedMap(pack.id) }
        assertTrue(ok)
        assertNull("suspect kept after a pick in Layers", suspect(guard))
        waitUntil(what = "picked pack up") { vm.mapSource.value is OfflineTileMapSourceAndroid && vm.shownEntryId() == pack.id }
        waitUntil(what = "no read settle") { armed(guard).isEmpty() }
        // so the next launch restores it instead of asking about a map they opened
        assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(guardFile).launchDecision(pack.id))
    }

    @Test
    fun aPackReopenedAsANewSourceMidWindowStaysArmedTillItsOwnFirstDraw() {
        val pack = packEntry()
        seed(pack)
        val guard = relaunch()
        val vm = viewModel()
        waitUntil(what = "restored") { vm.mapSource.value is OfflineTileMapSourceAndroid }
        onMain { }
        val first = vm.mapSource.value as OfflineTileMapSourceAndroid
        // a read the map view asked for and hasn't had back yet keeps the first window open
        val watch = requireNotNull(first.readWatch) { "no first draw window" }
        watch.readStarted()
        assertEquals(listOf(pack.id), armed(guard))

        // another writer moves the file (same bytes, so it's reopened from the admitted cache)
        val moved = File(files, "mbtiles/import-guard-moved-${UUID.randomUUID()}.mbtiles")
        assertTrue(File(files, pack.fileName).renameTo(moved))
        val current = (library.load() as LibraryLoad.Loaded).state
        val next = current.copy(entries = current.entries.map { if (it.id == pack.id) it.copy(fileName = "mbtiles/${moved.name}") else it })
        assertTrue(library.commit(next) is LibraryCommit.Written)
        onMain { vm.onMissionDataUnlocked() }

        val second = vm.mapSource.value
        assertTrue("not reopened as a new source: $second", second is OfflineTileMapSourceAndroid && second !== first)
        assertEquals("the reopen's marker went with the old window", listOf(pack.id), armed(guard))
        assertTrue("new publication unwatched", (second as OfflineTileMapSourceAndroid).readWatch != null)
        watch.readEnded(delivered = false)
        // nothing asks the new one for a tile, so its own window ends it
        waitUntil(what = "new window settled") { armed(guard).isEmpty() }
    }

    @Test
    fun theSavedPackIsAdmittedOffMainBehindABlankThatAsksForNothing() {
        // s14.2 (F3): the restore used to open + aggregate the whole pack on main at launch
        val pack = packEntry()
        seed(pack)
        val guard = relaunch()
        val gate = CountDownLatch(1)
        val seen = recordAdmissions({ guard }, mapOf(pack.fileName to gate))
        val vm = viewModel()
        // still being checked: blank, no tiles and no online provider, nothing written
        val blank = vm.mapSource.value
        assertTrue("not a blank while it's checked: $blank", blank is MbtilesPlaceholderSource)
        assertNull(blank.coverage)
        assertEquals(pack.id, activeId())
        gate.countDown()
        waitUntil(what = "pack up") { vm.mapSource.value is OfflineTileMapSourceAndroid }
        assertEquals(pack.id, vm.shownEntryId())
        assertEquals(1, seen.size)
        assertFalse("admitted on main", seen.single().onMain)
        assertEquals("guard armed on disk before the open", listOf(pack.id), seen.single().armedAtOpen)

        // the same bytes on another screen this process go up straight away, no second admission
        val second = viewModel()
        assertTrue(second.mapSource.value is OfflineTileMapSourceAndroid)
        assertEquals(1, seen.size)
    }

    @Test
    fun leavingAPreviewThatCameUpOverTheRestoresBlankPutsThePackBack() {
        val pack = packEntry()
        val sheet = sheetEntry()
        assertTrue(library.write(LibraryState(
            active = ActiveRef.entry(pack.id), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name, entries = listOf(pack, sheet),
        )))
        // E3 brings back a calibration on a sheet with no georef, so it comes back as a preview
        assertTrue(CalibrationDraftStore(files).save(CalibrationDraft(
            contentKey = sheet.contentKey!!, pageIndex = 0, entryId = sheet.id, datumId = "WGS84", active = true, updatedAtMs = 1,
        )))
        val guard = relaunch()
        val gate = CountDownLatch(1)
        val seen = recordAdmissions({ guard }, mapOf(pack.fileName to gate))
        val vm = viewModel()
        // the restore's blank went up, then the resumed preview on top of it (same main pass)
        val preview = vm.mapSource.value
        assertTrue("not the preview: $preview", (preview as? PdfMapSource)?.isPreview == true)
        assertTrue(vm.calibration.isActive)
        // the preview superseded the restore's open, so what it admitted is closed unshown
        gate.countDown()
        waitUntil(what = "superseded admission closed") { seen.singleOrNull()?.source?.isClosedForTesting() == true }
        onMain { }

        // Leave: the durable pack comes back, not a blank nobody's filling
        onMain {
            vm.calibration.leave(keep = true)
            vm.endCalibrationPreview()
        }
        waitUntil(what = "pack back after leave") { vm.mapSource.value is OfflineTileMapSourceAndroid }
        assertEquals(pack.id, vm.shownEntryId())
        assertEquals(pack.id, activeId())
        assertEquals("admitted twice", 1, seen.size)
    }

    @Test
    fun aQuickSecondPickWinsAndTheFirstPackIsClosedUnwritten() {
        val a = packEntry()
        val b = packEntry()
        assertTrue(library.write(LibraryState(
            active = ActiveRef.online(BasemapStyle.OSM_TOPO.name), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name, entries = listOf(a, b),
        )))
        val guard = relaunch()
        val gateA = CountDownLatch(1)
        val seen = recordAdmissions({ guard }, mapOf(a.fileName to gateA))
        val vm = viewModel()
        var ok = false
        onMain { ok = vm.activateImportedMap(a.id) }
        assertTrue(ok)
        // A's being checked: the map that was up stays and nothing's written yet
        assertTrue(vm.mapSource.value is OnlineRasterMapSourceAndroid)
        assertEquals(null, activeId())
        onMain { ok = vm.activateImportedMap(b.id) }
        assertTrue(ok)
        waitUntil(what = "B up") { vm.shownEntryId() == b.id }
        assertEquals(b.id, activeId())

        // A comes back late: closed, never written or shown, its marker gone
        gateA.countDown()
        waitUntil(what = "A closed") { seen.firstOrNull { it.path.endsWith(a.fileName) }?.source?.isClosedForTesting() == true }
        onMain { }
        assertEquals(b.id, vm.shownEntryId())
        assertEquals(b.id, activeId())
        assertFalse("A still armed", a.id in armed(guard))
        assertTrue("admitted on main", seen.none { it.onMain })
    }

    @Test
    fun anActivationThatComesBackLockedWritesNothingAndClosesThePack() {
        val a = packEntry()
        assertTrue(library.write(LibraryState(
            active = ActiveRef.online(BasemapStyle.OSM_TOPO.name), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name, entries = listOf(a),
        )))
        val guard = relaunch()
        val gate = CountDownLatch(1)
        val seen = recordAdmissions({ guard }, mapOf(a.fileName to gate))
        val vm = viewModel()
        var ok = false
        onMain { ok = vm.activateImportedMap(a.id) }
        assertTrue(ok)
        assertEquals(listOf(a.id), armed(guard))
        // Home locks the mission key while A's still being checked
        DataKey.lock()
        try {
            gate.countDown()
            waitUntil(what = "A closed") { seen.singleOrNull()?.source?.isClosedForTesting() == true }
            onMain { }
            assertTrue(vm.mapSource.value is OnlineRasterMapSourceAndroid)
            assertEquals("marker left behind", emptyList<String>(), armed(guard))
        } finally {
            DataKey.unlock()
        }
        assertNull("written behind the lock", activeId())
    }

    @Test
    fun aRestoreTheAdmissionRefusesGoesOnlineInMemoryOnly() {
        val pack = packEntry()
        seed(pack)
        val guard = relaunch()
        admitMbtilesPack = { _, _ -> null }
        val vm = viewModel()
        waitUntil(what = "online fallback") { vm.mapSource.value is OnlineRasterMapSourceAndroid }
        assertEquals("durable selection untouched", pack.id, activeId())
        assertNull(vm.pdfRecovery.value)
        assertEquals(emptyList<String>(), armed(guard))
        assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(guardFile).launchDecision(pack.id))
    }

    @Test
    fun aScreenThatGoesMidCheckClosesThePackAndDropsItsMarker() {
        val pack = packEntry()
        seed(pack)
        val guard = relaunch()
        val gate = CountDownLatch(1)
        val seen = recordAdmissions({ guard }, mapOf(pack.fileName to gate))
        viewModel()
        assertEquals(listOf(pack.id), armed(guard))
        onMain { stores.forEach { it.clear() } }
        stores.clear()
        gate.countDown()
        waitUntil(what = "closed") { seen.singleOrNull()?.source?.isClosedForTesting() == true }
        waitUntil(what = "marker gone") { armed(guard).isEmpty() }
    }

    @Test
    fun anImportedPackGoesUpOnTheWorkersAdmissionWithoutAnother() {
        assertTrue(library.write(LibraryState(
            active = ActiveRef.online(BasemapStyle.OSM_TOPO.name), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name,
        )))
        val guard = relaunch()
        val seen = recordAdmissions({ guard })
        val vm = viewModel()
        // made once the screen's up, like an import's copy (its launch reconcile would take an orphan)
        val pack = packEntry()
        val admitted = requireNotNull(MBTilesStore.open(File(files, pack.fileName).path)).use { it.metadata }
        var ok = false
        onMain { ok = vm.addImportedEntry(pack, activate = true, admitted = admitted) }
        assertTrue(ok)
        val shown = vm.mapSource.value as OfflineTileMapSourceAndroid
        assertEquals(pack.id, activeId())
        assertEquals("admitted again", 0, seen.size)
        assertEquals("010203", shown.tileData(8, 234, 103)?.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun aRestoreInTheBackgroundNeverArms() {
        val pack = packEntry()
        seed(pack)
        val guard = relaunch()
        PdfRenderExecutor.foreground = false
        val vm = viewModel()
        waitUntil(what = "restored") { vm.mapSource.value is OfflineTileMapSourceAndroid }
        assertEquals(emptyList<String>(), armed(guard))
        assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(guardFile).launchDecision(pack.id))
    }
}
