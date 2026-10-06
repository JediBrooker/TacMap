package com.tacmap.map

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.ActiveMapSelectionStore
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.ImportedMapLibraryStore
import com.tacmap.calibration.LibraryLoad
import com.tacmap.calibration.PdfBaker
import com.tacmap.util.MissionKeyUnlockRule
import com.tacmap.util.SafeStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * wp4-android-1 / gap-android-blockers-empirical-2, the reviewer's device steps through real
 * view models on the app's own files dir: a 2.x install with two MBTiles packs, a PDF, a
 * damaged active_map_source.json, no PDF session prefs and no retained selector. The first
 * restore quarantined the selector, then Retry or the next launch read "no legacy left" and
 * an authoritative Empty restore reconciled every map file away. Everything the view models
 * could touch is copied aside first and put back after, and the sealed-only ledger is a test
 * double so the app's own one never hears about these writes
 */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationSalvageInstrumentedTest {
    @get:Rule val missionKey = MissionKeyUnlockRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as Application
    private val files = context.filesDir
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backup = File(context.cacheDir, "salvage-backup-${System.nanoTime()}")
    private val roots = listOf("pdf_maps", "mbtiles", "offline_tiles", PdfBaker.WORK_DIR)
    private val stores = mutableListOf<ViewModelStore>()
    private var topLevel = emptySet<String>()
    private var savedPrefs = emptyMap<String, Any?>()
    private var previousPolicy: SafeStore.MigrationPolicy? = null
    private val ledger = java.util.Collections.synchronizedSet(HashSet<String>())
    private val sessionPrefs get() = context.getSharedPreferences("pdf_session", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        backup.mkdirs()
        topLevel = files.listFiles().orEmpty().map { it.name }.toSet()
        files.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(backup, it.name), overwrite = true) }
        for (r in roots) File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach {
            it.copyTo(File(backup, "$r/${it.name}"), overwrite = true)
            it.delete()
        }
        savedPrefs = sessionPrefs.all.toMap()
        assertTrue(sessionPrefs.edit().clear().commit())
        // a 2.x install has never written a library: no file, no marker, no ledger entry
        val lib = ImportedMapLibraryStore.FILE_NAME
        files.listFiles().orEmpty().filter {
            it.name == lib || it.name.startsWith("$lib.") || it.name == ".$lib.sealed-only-v1" ||
                it.name.startsWith("active_map_source.json") || it.name.startsWith("retained_imported_map_source.json") ||
                it.name == "calibration_drafts.json"
        }.forEach { it.delete() }
        previousPolicy = SafeStore.migrationPolicy
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in ledger
            override fun markSealedOnly(label: String) { ledger += label }
        }
    }

    @After
    fun tearDown() {
        (app as com.tacmap.app.TacticalApp).pdfBakeManager.cancel()
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        previousPolicy?.let { SafeStore.migrationPolicy = it }
        for (r in roots) {
            File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            File(backup, r).listFiles().orEmpty().forEach { it.copyTo(File(files, "$r/${it.name}"), overwrite = true) }
        }
        files.listFiles().orEmpty().filter { it.isFile && it.name !in topLevel }.forEach { it.delete() }
        backup.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(files, it.name), overwrite = true) }
        sessionPrefs.edit().clear().apply {
            savedPrefs.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Long -> putLong(k, v)
                    is Int -> putInt(k, v)
                }
            }
        }.commit()
        backup.deleteRecursively()
    }

    private fun viewModel(): MapViewModel {
        val store = ViewModelStore().also { stores += it }
        var vm: MapViewModel? = null
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MapViewModel::class.java]
        }
        return vm!!
    }

    private fun waitUntil(ms: Long = 60_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(25)
        }
    }

    /** the restore's off main (migration hop), wait for it to land and the IO sweep after it */
    private fun restored(vm: MapViewModel): MapViewModel {
        waitUntil(what = "restore") { vm.libraryStatus.value != LibraryStatus.LOADING }
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        return vm
    }

    private fun seed(rel: String, bytes: ByteArray): File =
        File(files, rel).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun pdf(rel: String): File {
        val file = File(files, rel).apply { parentFile!!.mkdirs() }
        PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle(600f, 400f))); doc.save(file) }
        return file
    }

    private fun assertIntact(seeded: List<File>, step: String) {
        val left = roots.associateWith { File(files, it).list()?.toList().orEmpty() }
        for (f in seeded) assertTrue("$step deleted ${f.parentFile!!.name}/${f.name}, left $left", f.isFile)
    }

    /** Loaded, every map file listed once, the sealed flag that keeps cleanup off it */
    private fun assertRecovered(vm: MapViewModel, seeded: List<File>, step: String) {
        assertEquals("$step status", LibraryStatus.LOADED, vm.libraryStatus.value)
        assertNull("$step left an issue up", vm.mapSelectionPersistenceIssue.value)
        val sealed = (ImportedMapLibraryStore(files).load() as? LibraryLoad.Loaded)?.state
            ?: throw AssertionError("$step: no library")
        assertEquals("$step flag", true, sealed.recoveryPreservesOrphans)
        assertEquals(
            "$step entries",
            seeded.map { "${it.parentFile!!.name}/${it.name}" }.sorted(),
            sealed.entries.map { it.fileName }.sorted(),
        )
    }

    @Test
    fun aQuarantinedSelectorNeverLetsRetryOrTheNextLaunchDeleteTheMaps() {
        val seeded = listOf(
            seed("mbtiles/1f2e3d4c-alpha.mbtiles", ByteArray(4096) { 1 }),
            seed("mbtiles/5a6b7c8d-bravo.mbtiles", ByteArray(4096) { 2 }),
            pdf("pdf_maps/9e8d7c6b-sf.pdf"),
        )
        seed("active_map_source.json", "not a selector, bit rot".toByteArray())

        val first = restored(viewModel())
        assertTrue("selector wasn't quarantined", files.list().orEmpty().any { it.startsWith("active_map_source.json.corrupt-") })
        assertIntact(seeded, "first restore")
        // 3.0.1: salvaged on the spot, not blocked behind an "unlock mission data" that can't work
        assertRecovered(first, seeded, "first restore")
        assertEquals(MapLaunchAlert.LibraryRecovered, first.launchAlert.value)

        // the locked issue's Retry (if one's up), then MapScreen coming back
        instrumentation.runOnMainSync { first.retryMapSelectionPersistence() }
        restored(first)
        instrumentation.runOnMainSync { first.onMissionDataUnlocked() }
        restored(first)
        assertIntact(seeded, "Retry")

        // every transition works again and keeps the flag
        var picked = false
        instrumentation.runOnMainSync { picked = first.selectBaseMap(BasemapStyle.OSM_STREET) }
        assertTrue("basemap change refused", picked)
        assertIntact(seeded, "basemap change")

        // and a cold launch: the same library, told once only, the quarantine copy never cleared
        val next = restored(viewModel())
        assertIntact(seeded, "next launch")
        assertRecovered(next, seeded, "next launch")
        assertNull(next.launchAlert.value)
        assertTrue(files.list().orEmpty().any { it.startsWith("active_map_source.json.corrupt-") })
    }

    @Test
    fun mapFilesWithNoLibraryAndNoOldStoresAreAdoptedNotReconciledAway() {
        // gap-android-blockers-empirical-2: an Empty restore with nothing to migrate used to be
        // authoritative, its reconcile deleted whatever sat in the map dirs
        val seeded = listOf(
            seed("mbtiles/import-0a0b0c0d0e0f0a0b.mbtiles", ByteArray(4096) { 3 }),
            pdf("pdf_maps/import-1a1b1c1d1e1f1a1b.pdf"),
        )
        val bake = seed("offline_tiles/tacmap-bake-orphan.mbtiles", ByteArray(2048) { 4 })

        val first = restored(viewModel())
        assertIntact(seeded + bake, "first restore")
        assertRecovered(first, seeded, "first restore")
        assertEquals(MapLaunchAlert.LibraryRecovered, first.launchAlert.value)

        val next = restored(viewModel())
        assertIntact(seeded + bake, "next launch")
        assertRecovered(next, seeded, "next launch")
        assertNull(next.launchAlert.value)
    }

    /** a 2.x selector: online active, [pack] retained, so nothing has to open at restore */
    private fun legacySelectorRetaining(pack: File) {
        val legacy = ActiveMapSelectionStore(context)
        assertTrue(legacy.saveOnline(BasemapStyle.OSM_STREET))
        assertTrue(legacy.saveRetainedOffline(pack.path, BasemapStyle.OSM_STREET))
    }

    @Test
    fun aFirstLibraryWriteThatDiedBeforeItsRenameIsSalvagedWithTheOldNames() {
        // F2 (WP4 s14.3): the ledger names the library but there's no file and no marker, and the
        // 2.x selector is still waiting. 3.0.1 read that as corrupt and its Retry rebuilt the
        // retained pack as Recovered map n, its name stuck in the old selector
        val pack = seed("mbtiles/import-2a2b2c2d2e2f2a2b.mbtiles", ByteArray(4096) { 5 })
        val sheet = pdf("pdf_maps/import-3a3b3c3d3e3f3a3b.pdf")
        legacySelectorRetaining(pack)
        ledger += ImportedMapLibraryStore.LABEL

        val first = restored(viewModel())
        assertIntact(listOf(pack, sheet), "first restore")
        assertRecovered(first, listOf(pack, sheet), "first restore")
        assertEquals(MapLaunchAlert.LibraryRecovered, first.launchAlert.value)
        val sealed = (ImportedMapLibraryStore(files).load() as LibraryLoad.Loaded).state
        assertEquals("import-2a2b2c2d2e2f2a2b", sealed.entries.single { it.fileName == "mbtiles/${pack.name}" }.displayName)
        // a salvage clears nothing, the old selector stays frozen
        assertTrue(File(files, "active_map_source.json").isFile)

        val next = restored(viewModel())
        assertIntact(listOf(pack, sheet), "next launch")
        assertRecovered(next, listOf(pack, sheet), "next launch")
        assertNull(next.launchAlert.value)
    }

    @Test
    fun aLibraryThatGoesMissingUnderTheScreenIsSalvagedByItsRetryNotRebuilt() {
        // L1 (3.0.2 deferral): a salvaged library with the 2.x selector frozen next to it, then
        // the library file and its marker go while the screen's up (the ledger stays). A launch
        // salvages that, but the in-session corrupt issue's Retry ran the S2 rebuild: the pack
        // came back as Recovered map 1 and its old name was stuck in the selector for good
        val pack = seed("mbtiles/import-5a5b5c5d5e5f5a5b.mbtiles", ByteArray(4096) { 7 })
        legacySelectorRetaining(pack)
        ledger += ImportedMapLibraryStore.LABEL
        val vm = restored(viewModel())
        assertRecovered(vm, listOf(pack), "first restore")
        instrumentation.runOnMainSync { vm.dismissLaunchAlert() }

        val lib = ImportedMapLibraryStore.FILE_NAME
        assertTrue(File(files, lib).delete())
        assertTrue(File(files, ".$lib.sealed-only-v1").delete())
        assertEquals(LibraryLoad.Unfinished, ImportedMapLibraryStore(files).load())
        // MapScreen coming back reads the sealed library again and finds it gone
        instrumentation.runOnMainSync { vm.onMissionDataUnlocked() }
        assertEquals(LibraryStatus.CORRUPT, vm.libraryStatus.value)
        assertNotNull(vm.mapSelectionPersistenceIssue.value)

        instrumentation.runOnMainSync { vm.retryMapSelectionPersistence() }
        waitUntil(what = "retry") { vm.libraryStatus.value == LibraryStatus.LOADED }
        restored(vm)
        assertIntact(listOf(pack), "retry")
        assertRecovered(vm, listOf(pack), "retry")
        val sealed = (ImportedMapLibraryStore(files).load() as LibraryLoad.Loaded).state
        assertEquals("import-5a5b5c5d5e5f5a5b", sealed.entries.single().displayName)
        assertEquals(MapLaunchAlert.LibraryRecovered, vm.launchAlert.value)
        assertTrue(File(files, "active_map_source.json").isFile)
    }

    @Test
    fun aFreshInstallWithOnlyAKilledImportsPartialIsAFirstLaunchNotARecovery() {
        // F4 (3.0.1 gate): the first import ever is killed mid copy and leaves only its .partial.
        // that used to adopt nothing into a flagged library with the recovered notice, and no
        // cleanup ever ran on the install again
        val partial = seed("pdf_maps/import-6a6b6c6d6e6f6a6b.pdf.partial", ByteArray(2048) { 8 })
        val sidecar = seed("mbtiles/import-7a7b7c7d7e7f7a7b.mbtiles-wal", ByteArray(512) { 9 })

        val vm = restored(viewModel())
        assertEquals(LibraryStatus.LOADED, vm.libraryStatus.value)
        assertNull(vm.launchAlert.value)
        assertNull(vm.mapSelectionPersistenceIssue.value)
        // nothing written: still a first launch, and its reconcile cleared what the copy left
        assertEquals(LibraryLoad.Empty, ImportedMapLibraryStore(files).load())
        assertFalse(partial.exists())
        assertFalse(sidecar.exists())

        // and the next launch is a plain first launch too
        val next = restored(viewModel())
        assertNull(next.launchAlert.value)
        assertEquals(LibraryLoad.Empty, ImportedMapLibraryStore(files).load())
    }

    @Test
    fun aLibraryWriteThatFailsOnAFullDiskIsPendingAndItsRetryStillMigrates() {
        // F2 through the real view model and SafeStore: the library's temp file can't be written.
        // 3.0.1 had the ledger down before that, read the library back corrupt and only offered
        // the rebuild
        val pack = seed("mbtiles/import-4a4b4c4d4e4f4a4b.mbtiles", ByteArray(4096) { 6 })
        legacySelectorRetaining(pack)
        val jam = File(files, "${ImportedMapLibraryStore.FILE_NAME}.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        try {
            val vm = restored(viewModel())
            // pending: the locked issue with Retry, nothing on record, nothing cleared
            assertEquals(LibraryStatus.LOCKED, vm.libraryStatus.value)
            assertNotNull(vm.mapSelectionPersistenceIssue.value)
            assertFalse("ledger marked for a write that never landed", ImportedMapLibraryStore.LABEL in ledger)
            assertEquals(LibraryLoad.Empty, ImportedMapLibraryStore(files).load())
            assertTrue(File(files, "active_map_source.json").isFile)
            assertIntact(listOf(pack), "blocked restore")

            jam.deleteRecursively()
            instrumentation.runOnMainSync { vm.retryMapSelectionPersistence() }
            waitUntil(what = "retry") { vm.libraryStatus.value == LibraryStatus.LOADED }
            restored(vm)
            // the migration it was: the old name, a library that may clean up, the old selector gone
            val sealed = (ImportedMapLibraryStore(files).load() as LibraryLoad.Loaded).state
            assertTrue(sealed.permitsCleanup)
            assertEquals("import-4a4b4c4d4e4f4a4b", sealed.entries.single().displayName)
            assertFalse(File(files, "active_map_source.json").exists())
            assertIntact(listOf(pack), "retry")
        } finally {
            jam.deleteRecursively()
        }
    }
}
