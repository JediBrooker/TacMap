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
import com.tacmap.calibration.LibraryLoad
import com.tacmap.calibration.LibraryState
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfCalibrationIdentity
import com.tacmap.map.render.MbtilesLaunchDecision
import com.tacmap.map.render.MbtilesOpenGuard
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.pdf.PdfRenderExecutor
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
