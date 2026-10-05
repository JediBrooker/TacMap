package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.CalibrationTarget
import com.tacmap.calibration.fiducial.StoredPagePoint
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The two sealed files of contract s8: imported_map_library.json (the one
 * authority) and calibration_drafts.json. Plus the reconcile that runs after
 * every library write.
 */
class ImportedMapLibraryStoreTest {
    private val sealedLabels = mutableSetOf<String>()
    private var locked = false
    private val key = ByteArray(32) { (it + 3).toByte() }

    @Before
    fun installTestKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { if (locked) throw DataKey.LockedException() else key }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
        InFlightImportFiles.clear()
    }

    @After
    fun restore() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
        InFlightImportFiles.clear()
    }

    private fun tempDir(): File = Files.createTempDirectory("library").toFile()

    private fun entry(dir: File, id: String, kind: String = "pdf", name: String = "Kestrel Ridge 1:25k"): ImportedMapEntry {
        val sub = if (kind == "pdf") "pdf_maps" else "mbtiles"
        val f = File(dir, "$sub/import-$id.${if (kind == "pdf") "pdf" else "mbtiles"}").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { 1 }) }
        return ImportedMapEntry(
            id = id, kind = kind, fileName = "$sub/${f.name}", displayName = name,
            contentKey = "sha256:" + id.padEnd(64, '0'), byteCount = f.length(), fileModifiedAtMs = f.lastModified(), importedAtMs = 1,
            pdf = if (kind == "pdf") PdfEntryInfo(1, 0, 0, listOf(listOf(0.0, 0.0), listOf(10.0, 0.0), listOf(10.0, 10.0), listOf(0.0, 10.0))) else null,
        )
    }

    @Test
    fun theLibraryRoundTripsSealedWithNoNameOrPathInPlaintext() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        assertEquals(LibraryLoad.Empty, store.load())
        val state = LibraryState(active = ActiveRef.entry("a"), preferredOnlineStyle = "ESRI_SATELLITE", entries = listOf(entry(dir, "a")))
        assertTrue(store.write(state))
        assertEquals(LibraryLoad.Loaded(state), store.load())
        // the display name (the picked file's stem) only ever lives in the sealed file
        val bytes = File(dir, ImportedMapLibraryStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("Kestrel"))
        assertFalse(bytes.contains("pdf_maps"))
    }

    @Test
    fun lockedAndTamperedLibrariesAreNotEmpty() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        assertTrue(store.write(LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO")))
        locked = true
        assertEquals(LibraryLoad.Locked, store.load())
        assertFalse("no writes while locked", store.write(LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO")))
        locked = false
        val f = File(dir, ImportedMapLibraryStore.FILE_NAME)
        f.writeBytes(f.readBytes().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() })
        assertEquals(LibraryLoad.Corrupt, store.load())
        // and it stays corrupt (quarantined), never quietly a fresh empty library
        assertEquals(LibraryLoad.Corrupt, store.load())
    }

    @Test
    fun aNewerSchemaIsCorruptFromHereNotEmpty() {
        val dir = tempDir()
        SafeStore.writeAtomically(File(dir, ImportedMapLibraryStore.FILE_NAME), ImportedMapLibraryStore.LABEL,
            """{"schemaVersion":2,"active":{"kind":"online","style":"OSM_TOPO"},"preferredOnlineStyle":"OSM_TOPO","entries":[]}""")
        assertEquals(LibraryLoad.Corrupt, ImportedMapLibraryStore(dir).load())
    }

    @Test
    fun entryFileNamesCantEscapeTheMapDirectories() {
        val dir = tempDir()
        listOf("../secret.pdf", "/etc/x.pdf", "pdf_maps/../x.pdf", "pdf_maps/a/b.pdf", "other/x.pdf", "pdf_maps/x.txt", "pdf_maps\\x.pdf", "pdf_maps/..")
            .forEach { assertNull(it, ImportedMapLibraryStore.resolveManaged(dir, it)) }
        assertEquals(File(dir.canonicalFile, "mbtiles/import-1.mbtiles"), ImportedMapLibraryStore.resolveManaged(dir, "mbtiles/import-1.mbtiles"))
    }

    @Test
    fun fileStatusIsSizeAndMtimeOnlyNoHashing() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        val e = entry(dir, "a")
        assertEquals(EntryFileStatus.OK, store.fileStatus(e))
        assertEquals(EntryFileStatus.MISMATCH, store.fileStatus(e.copy(byteCount = e.byteCount + 1)))
        store.fileOf(e)!!.delete()
        assertEquals(EntryFileStatus.MISSING, store.fileStatus(e))
    }

    @Test
    fun reconcileKeepsEveryEntryItsSidecarsAndInFlightCopiesButNothingElse() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        val pdf = entry(dir, "a")
        val tiles = entry(dir, "b", kind = "mbtiles")
        val wal = File(dir, "mbtiles/import-b.mbtiles-wal").apply { writeText("w") }
        val orphan = File(dir, "pdf_maps/import-gone.pdf").apply { writeText("x") }
        val oldPartial = File(dir, "pdf_maps/import-dead.pdf.partial").apply { writeText("x") }
        // an import mid copy: registered before the .partial exists (s9.3)
        val copying = File(dir, "pdf_maps/import-new.pdf.partial")
        InFlightImportFiles.register(copying)
        copying.writeText("half")
        val state = LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO", entries = listOf(pdf, tiles))
        val ok = ManagedImportedMapFileLifecycle.reconcile(
            managedParent = dir,
            directories = ImportedMapLibraryStore.MANAGED_DIRECTORIES.map { File(dir, it) },
            keeping = store.managedFiles(state).filter { it.exists() }.toSet(),
            inFlight = InFlightImportFiles.snapshot(),
        )
        assertTrue(ok)
        assertTrue(store.fileOf(pdf)!!.exists())
        assertTrue(store.fileOf(tiles)!!.exists())
        assertTrue("sidecar kept with its MBTiles", wal.exists())
        assertTrue("in flight copy survives the reconcile", copying.exists())
        assertFalse(orphan.exists())
        assertFalse(oldPartial.exists())
    }

    // ------------------------------------------------------------------ guarded writes

    private fun ok(r: LibraryReduction): LibraryState = (r as LibraryReduction.Ok).state

    private fun written(c: LibraryCommit): LibraryState = (c as LibraryCommit.Written).state

    private fun online(style: String = "OSM_TOPO") = LibraryState(active = ActiveRef.online(style), preferredOnlineStyle = style)

    private val georef = PdfGeoreference(
        page = 0,
        crs = GeoCrs.Geographic,
        datum = GeoDatums.WGS84,
        affine = PlaneAffine(1e-4, 0.0, 151.0, 0.0, 1e-4, -33.0),
        crop = listOf(PagePoint(0.0, 0.0), PagePoint(10.0, 0.0), PagePoint(10.0, 10.0), PagePoint(0.0, 10.0)),
        origin = GeorefOrigin.ADOBE_VP,
    )

    @Test
    fun aStaleCopyCanNeverShrinkTheLibraryOrPutBackAnOldCalibration() {
        // two holders on one sealed library, like a second MainActivity's view model or a bake
        // that outlived its screen: each has the copy it read, only a current one may write
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        val p = entry(dir, "p")
        written(store.commit(online().copy(active = ActiveRef.entry("p"), entries = listOf(p))))
        val a = (store.load() as LibraryLoad.Loaded).state
        val b = (store.load() as LibraryLoad.Loaded).state

        // B imports M and calibrates P
        val m = entry(dir, "m")
        val b1 = written(store.commit(ok(LibraryReducer.apply(LibraryTransition.AddEntry(m, activate = false), b))))
        val manual = ManualCalibration("WGS84", emptyList(), PdfGeoreferenceCodec.encode(georef), 3, savedAtMs = 9)
        val b2 = written(store.commit(ok(LibraryReducer.apply(LibraryTransition.CommitCalibration("p", manual, p.contentKey!!, 0), b1))))

        // A, still on the copy from before both, picks a basemap: refused, nothing written
        val stale = store.commit(ok(LibraryReducer.apply(LibraryTransition.SelectOnline("ESRI_TOPO"), a)))
        assertTrue("$stale", stale is LibraryCommit.Stale)
        assertEquals(LibraryLoad.Loaded(b2), store.load())
        // and cleanup keeps by the sealed library, whatever A thinks
        assertEquals(true, LibraryMapFiles.reconcile(dir) { (store.load() as LibraryLoad.Loaded).state })
        assertTrue(store.fileOf(m)!!.isFile)

        // A rebases on what's there now and its pick lands on top of B's work
        val fresh = ((stale as LibraryCommit.Stale).current as LibraryLoad.Loaded).state
        val landed = written(store.commit(ok(LibraryReducer.apply(LibraryTransition.SelectOnline("ESRI_TOPO"), fresh))))
        assertEquals(listOf("p", "m"), landed.entries.map { it.id })
        assertEquals(manual, landed.entry("p")!!.pdf!!.manual)
        assertEquals(ActiveRef.online("ESRI_TOPO"), landed.active)
        assertEquals(LibraryLoad.Loaded(landed), store.load())
    }

    @Test
    fun twoFirstLaunchCopiesOnlyGetOneWriteBetweenThem() {
        // nothing written yet: both screens start from the same unwritten library (generation 0)
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        assertEquals(LibraryLoad.Empty, store.load())
        val first = written(store.commit(online().copy(entries = listOf(entry(dir, "a")))))
        assertTrue(first.generation > 0)
        assertTrue(store.commit(online("OSM_STREET")) is LibraryCommit.Stale)
        assertEquals(LibraryLoad.Loaded(first), store.load())
    }

    @Test
    fun nothingGoesDownWhileLockedOrOverAnUnreadableLibrary() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        val there = written(store.commit(online()))
        locked = true
        assertEquals(LibraryCommit.Stale(LibraryLoad.Locked), store.commit(there.copy(preferredOnlineStyle = "OSM_STREET")))
        assertEquals(LibraryCommit.Stale(LibraryLoad.Locked), store.create(online("OSM_STREET")))
        locked = false
        assertEquals(LibraryLoad.Loaded(there), store.load())
        val f = File(dir, ImportedMapLibraryStore.FILE_NAME)
        f.writeBytes(f.readBytes().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() })
        assertEquals(LibraryCommit.Stale(LibraryLoad.Corrupt), store.commit(there))
        assertFalse("a stale copy never stands in for a corrupt library", f.exists())
    }

    @Test
    fun aKeyStoreFailureOnTheReReadWritesNothing() {
        // every transition reads the sealed library again now: a key store error there is a
        // refused write like it always was, never a crash and never a write on a guess
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        val there = written(store.commit(online()))
        SafeStore.keyProvider = SafeStore.KeyProvider { throw IllegalStateException("keystore hiccup") }
        assertEquals(LibraryLoad.Locked, store.loadCurrent())
        assertEquals(LibraryCommit.Stale(LibraryLoad.Locked), store.commit(there.copy(preferredOnlineStyle = "OSM_STREET")))
        assertEquals(LibraryCommit.Stale(LibraryLoad.Locked), store.create(online("OSM_STREET")))
        SafeStore.keyProvider = SafeStore.KeyProvider { key }
        assertEquals(LibraryLoad.Loaded(there), store.load())
    }

    @Test
    fun createNeverWritesOverALibraryThatLoads() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        val there = written(store.create(online().copy(entries = listOf(entry(dir, "a")))))
        assertEquals(LibraryCommit.Stale(LibraryLoad.Loaded(there)), store.create(online("OSM_STREET")))
        assertEquals(LibraryLoad.Loaded(there), store.load())
    }

    @Test
    fun aRebuiltLibraryNeverReusesAGenerationAnOldCopyHolds() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        var s = written(store.commit(online()))
        repeat(3) { s = written(store.commit(s.copy(preferredOnlineStyle = "OSM_STREET"))) }
        val old = s
        // the file goes bad and gets rebuilt from scratch
        val f = File(dir, ImportedMapLibraryStore.FILE_NAME)
        f.writeBytes(f.readBytes().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() })
        assertEquals(LibraryLoad.Corrupt, store.load())
        var rebuilt = written(store.create(online().copy(recoveryPreservesOrphans = true)))
        // however many writes follow, the copy from before never matches by accident
        repeat(old.generation.toInt() + 2) {
            assertTrue("write ${it + 1}", store.commit(old.copy(preferredOnlineStyle = "ESRI_TOPO")) is LibraryCommit.Stale)
            rebuilt = written(store.commit(rebuilt))
        }
        assertEquals(LibraryLoad.Loaded(rebuilt), store.load())
    }

    // ------------------------------------------------------------------ drafts (s8.1)

    private fun draft(contentKey: String, page: Int = 0, at: Long = 0L, points: Int = 1) = CalibrationDraft(
        contentKey = contentKey, pageIndex = page, entryId = "e-$contentKey",
        datumId = "WGS84",
        points = (1..points).map { CalibrationPoint("p$it", it, StoredPagePoint(it.toDouble(), 1.0), "x", CalibrationReference.Geographic(-33.0, 151.0)) },
        nextNumber = points + 1, active = true, updatedAtMs = at,
    )

    @Test
    fun draftsRoundTripUnderTheirContentKeyAndPage() {
        val limits = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject
        assertEquals("<contentKey>#<pageIndex>", limits["lifecycle"]!!.jsonObject["draft"]!!.jsonObject["keyFormat"]!!.jsonPrimitive.content)
        val dir = tempDir()
        val store = CalibrationDraftStore(dir)
        val d = draft("sha256:aa", page = 3, points = 4)
        assertEquals("sha256:aa#3", d.key)
        assertEquals(CalibrationTarget.draftKey("sha256:aa", 3), d.key)
        assertTrue(store.save(d))
        assertEquals(d, store.load("sha256:aa#3"))
        assertNull(store.load("sha256:aa#0"))
        val bytes = File(dir, CalibrationDraftStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("sealed", bytes.contains("sha256:aa"))
        assertTrue(store.delete(d.key))
        assertNull(store.load(d.key))
    }

    @Test
    fun aDraftFiledUnderTheWrongKeyIsDroppedNotApplied() {
        // D5-10: hand-craft a document whose map key doesn't match the draft inside it
        val dir = tempDir()
        val good = draft("sha256:aa")
        val doc = """{"schemaVersion":1,"drafts":{"sha256:bb#0":${Json.encodeToString(CalibrationDraft.serializer(), good)}}}"""
        SafeStore.writeAtomically(File(dir, CalibrationDraftStore.FILE_NAME), CalibrationDraftStore.LABEL, doc)
        val store = CalibrationDraftStore(dir)
        assertNull(store.load("sha256:bb#0"))
        assertTrue(store.all().none { it.contentKey == "sha256:bb" })
    }

    @Test
    fun theSixteenMostRecentDraftsAreKept() {
        val limits = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject
        val max = limits["lifecycle"]!!.jsonObject["draft"]!!.jsonObject["maxDrafts"]!!.jsonPrimitive.int
        assertEquals(ImportLimits.MAX_DRAFTS, max)
        val store = CalibrationDraftStore(tempDir())
        (0 until max + 4).forEach { assertTrue(store.save(draft("sha256:k$it", at = it.toLong()))) }
        val kept = store.all().map { it.contentKey }.toSet()
        assertEquals(max, kept.size)
        // LRU by updatedAt: the 4 oldest went
        assertEquals((4 until max + 4).map { "sha256:k$it" }.toSet(), kept)
    }

    @Test
    fun aLockedKeyRefusesTheDraftWriteAndACorruptFileIsQuarantined() {
        val dir = tempDir()
        val store = CalibrationDraftStore(dir)
        locked = true
        assertFalse(store.save(draft("sha256:aa")))
        locked = false
        assertTrue(store.save(draft("sha256:aa")))
        val f = File(dir, CalibrationDraftStore.FILE_NAME)
        f.writeBytes(f.readBytes().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() })
        // unreadable: nothing comes back, but new points can still be saved
        assertNull(store.load("sha256:aa#0"))
        assertTrue(store.save(draft("sha256:cc")))
        assertTrue(dir.listFiles()!!.any { it.name.startsWith(CalibrationDraftStore.FILE_NAME + ".corrupt") })
    }

    @Test
    fun pruneDropsDraftsWhoseFileLeftTheLibrary() {
        val store = CalibrationDraftStore(tempDir())
        store.save(draft("sha256:keep"))
        store.save(draft("sha256:gone"))
        assertTrue(store.prune(setOf("sha256:keep")))
        assertEquals(listOf("sha256:keep"), store.all().map { it.contentKey })
    }
}
