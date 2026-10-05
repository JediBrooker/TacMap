package com.tacmap.calibration

import com.tacmap.map.render.pdf.PdfBakePlan
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * A bake that finishes with no MapViewModel alive (TacMap swiped away mid bake, a recording or
 * Unit Sync kept the process up) goes straight into the sealed library through the app scoped
 * fallback, with the same reducer rules and the same generation checked commit a screen uses.
 * It used to come back writeFailed and PdfBaker deleted the finished tiles.
 */
class LibraryBakeRecorderTest {
    private lateinit var files: File
    private var locked = false
    private val key = ByteArray(32) { (it + 7).toByte() }
    private val sealedLabels = mutableSetOf<String>()
    private val token = UUID.randomUUID().toString()

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
        files = Files.createTempDirectory("library-bake-recorder").toFile()
        SafeStore.keyProvider = SafeStore.KeyProvider { if (locked) throw DataKey.LockedException() else key.copyOf() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
        InFlightImportFiles.clear()
    }

    @After
    fun tearDown() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
        InFlightImportFiles.clear()
        files.deleteRecursively()
    }

    private fun entry(id: String) = ImportedMapEntry(
        id = id, kind = "pdf", fileName = "pdf_maps/import-$id.pdf", displayName = "Sheet $id",
        contentKey = "sha256:$id", byteCount = 4, fileModifiedAtMs = 1, importedAtMs = 1,
        pdf = PdfEntryInfo(
            pageCount = 1, pageIndex = 0, rotate = 0, pageBox = box,
            embedded = PdfGeoreferenceCodec.encode(georef), renderGuardToken = token,
        ),
    )

    private fun bake() = PersistedPdfBake(
        "tacmap-bake-${UUID.randomUUID()}.mbtiles", PdfBakePlan.bakeKey(georef.canonicalJson(), 256), 0, 12, 256, 1,
    )

    private fun written(vararg entries: ImportedMapEntry): ImportedMapLibraryStore {
        val store = ImportedMapLibraryStore(files)
        assertTrue(store.write(LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO", entries = entries.toList())))
        return store
    }

    private fun ImportedMapLibraryStore.loaded(): LibraryState = (load() as LibraryLoad.Loaded).state

    @Test
    fun withNoScreenAliveTheBakeIsRecordedInTheSealedLibrary() {
        val store = written(entry("a"))
        val before = store.loaded()
        val recorders = PdfBakeRecorders(fallback = LibraryBakeRecorder(files))
        val bake = bake()

        assertEquals(PdfBakeAttach.Attached, recorders.atPublish.attach("a", "sha256:a", token, bake))
        val after = store.loaded()
        assertEquals(bake, after.entry("a")!!.pdf!!.bake)
        assertTrue("written as a new generation", after.generation > before.generation)

        // a cancel that lands during the publish takes it back off the same way
        assertTrue(recorders.atPublish.detach("a", bake))
        assertNull(store.loaded().entry("a")!!.pdf!!.bake)
    }

    @Test
    fun aLockedLibraryRefusesAndWritesNothing() {
        val store = written(entry("a"))
        val before = File(files, ImportedMapLibraryStore.FILE_NAME).readBytes()
        locked = true
        assertEquals(PdfBakeAttach.WriteFailed(null), LibraryBakeRecorder(files).attach("a", "sha256:a", token, bake()))
        locked = false
        assertTrue(before.contentEquals(File(files, ImportedMapLibraryStore.FILE_NAME).readBytes()))
        assertNull(store.loaded().entry("a")!!.pdf!!.bake)
    }

    @Test
    fun aLibraryThatWasNeverWrittenRefuses() {
        assertEquals(PdfBakeAttach.WriteFailed(null), LibraryBakeRecorder(files).attach("a", "sha256:a", token, bake()))
        assertFalse(File(files, ImportedMapLibraryStore.FILE_NAME).exists())
    }

    @Test
    fun anEntryThatMovedOnIsSourceChangedAndNothingIsWritten() {
        val store = written(entry("a"))
        val generation = store.loaded().generation
        val recorder = LibraryBakeRecorder(files)
        // reimported bytes, a new guard token, a deleted entry
        assertEquals(PdfBakeAttach.SourceChanged, recorder.attach("a", "sha256:other", token, bake()))
        assertEquals(PdfBakeAttach.SourceChanged, recorder.attach("a", "sha256:a", "another-token", bake()))
        assertEquals(PdfBakeAttach.SourceChanged, recorder.attach("gone", "sha256:gone", token, bake()))
        assertEquals(PdfBakeAttach.SourceChanged, recorder.attach(null, "sha256:a", token, bake()))
        assertEquals(generation, store.loaded().generation)
    }

    @Test
    fun aLiveScreenStillRecordsBeforeTheFallback() {
        written(entry("a"))
        val screen = object : PdfBakeRecorder {
            var attached = 0
            override fun attach(entryId: String?, contentKey: String?, renderGuardToken: String, bake: PersistedPdfBake): PdfBakeAttach {
                attached++
                return PdfBakeAttach.Attached
            }
            override fun detach(entryId: String?, bake: PersistedPdfBake) = true
        }
        val recorders = PdfBakeRecorders(fallback = LibraryBakeRecorder(files))
        recorders.register(screen)
        assertEquals(PdfBakeAttach.Attached, recorders.atPublish.attach("a", "sha256:a", token, bake()))
        assertEquals(1, screen.attached)
        assertNull("the screen's write, not the fallback's", ImportedMapLibraryStore(files).loaded().entry("a")!!.pdf!!.bake)
    }
}
