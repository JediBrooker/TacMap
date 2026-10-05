package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.map.render.pdf.GuardKind
import com.tacmap.map.render.pdf.GuardResolution
import com.tacmap.map.render.pdf.PdfRenderGuard
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * import_limits.json libraryLoad + lifecycle.autoResume + the corrupt-library rebuild (S1-S4,
 * D8, F3, E3), run through the real store, SafeStore and the rules MapViewModel restores with.
 */
class LibraryRecoveryTest {
    private val limits = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject
    private val sealedLabels = mutableSetOf<String>()
    private var locked = false
    private val key = ByteArray(32) { (it + 7).toByte() }

    @Before
    fun installTestKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { if (locked) throw DataKey.LockedException() else key.copyOf() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String): Boolean {
                if (locked) throw DataKey.LockedException()
                return label in sealedLabels
            }
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

    private fun tempDir(): File = Files.createTempDirectory("recovery").toFile()
    private val libraryName = ImportedMapLibraryStore.FILE_NAME

    private fun state() = LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO")

    /** lay the files out the way the row's `given` says, then load through the real store */
    private fun loadFor(given: JsonObject): LibraryLoad {
        sealedLabels.clear()
        locked = false
        val dir = tempDir()
        val file = File(dir, libraryName)
        val store = ImportedMapLibraryStore(dir)
        when (val f = given["libraryFile"]!!.jsonPrimitive.content) {
            "ok" -> assertTrue(store.write(state().copy(recoveryPreservesOrphans = given["recoveryPreservesOrphans"]?.jsonPrimitive?.boolean)))
            // not a sealed blob and not json either: SafeStore quarantines it
            "unreadable" -> file.writeBytes(ByteArray(48) { (it * 31).toByte() })
            "newerSchema" -> SafeStore.writeAtomically(
                file, ImportedMapLibraryStore.LABEL,
                """{"schemaVersion":99,"active":{"kind":"online","style":"OSM_TOPO"},"preferredOnlineStyle":"OSM_TOPO","entries":[]}""",
            )
            "absent" -> Unit
            else -> error("libraryFile $f")
        }
        if (given["libraryFile"]!!.jsonPrimitive.content == "absent") {
            // a write that happened long ago leaves our record behind, nothing else
            if (given["writtenBefore"]!!.jsonPrimitive.boolean) sealedLabels += ImportedMapLibraryStore.LABEL
        }
        if (given["corruptSibling"]!!.jsonPrimitive.boolean) File(dir, "$libraryName.corrupt-1700000000000").writeBytes(ByteArray(8))
        locked = given["missionKey"]!!.jsonPrimitive.content == "locked"
        return store.load()
    }

    private fun legacyOf(code: String): LegacyLibraryState =
        LegacyLibraryState.entries.firstOrNull { it.code == code } ?: error("legacy $code")

    @Test
    fun everyLibraryLoadRowRestoresTheSameWayOnAndroid() {
        val rows = limits["libraryLoad"]!!.jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
        assertTrue("libraryLoad rows", rows.size >= 14)
        for (row in rows) {
            val id = row["id"]!!.jsonPrimitive.content
            val given = row["given"]!!.jsonObject
            val expect = row["expect"]!!.jsonObject
            val plan = LibraryRestoreRules.plan(loadFor(given), legacyOf(given["legacy"]!!.jsonPrimitive.content))
            assertEquals("$id status", expect["status"]!!.jsonPrimitive.content, plan.status.code)
            assertEquals("$id migration", expect["migration"]!!.jsonPrimitive.content, plan.migration.code)
            for (k in listOf("reconcile", "bakeSweep", "draftPrune")) {
                assertEquals("$id $k", expect[k]!!.jsonPrimitive.boolean, plan.authoritative)
            }
            assertEquals("$id clearLegacy", expect["clearLegacy"]!!.jsonPrimitive.boolean, plan.clearLegacy)
            val issue = expect["issue"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            assertEquals("$id issue", issue, plan.issue?.code)
        }
        // and the store half of S1 on its own: the second load after a quarantine isn't empty
        val dir = tempDir()
        File(dir, libraryName).writeBytes(ByteArray(40) { 3 })
        val store = ImportedMapLibraryStore(dir)
        assertEquals(LibraryLoad.Corrupt, store.load())
        assertFalse(File(dir, libraryName).exists())
        assertEquals(LibraryLoad.Corrupt, store.load())
    }

    @Test
    fun aLibraryThatVanishedAfterBeingWrittenIsCorruptEvenWithItsMarkerGone() {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        assertEquals(LibraryLoad.Empty, store.load())
        assertTrue(store.write(state()))
        File(dir, libraryName).delete()
        // the plaintext marker next to it says so
        assertEquals(LibraryLoad.Corrupt, store.load())
        // and with that gone too, the sealed ledger still does
        dir.listFiles()!!.filter { it.name.contains("sealed-only") }.forEach { it.delete() }
        assertEquals(LibraryLoad.Corrupt, store.load())
        // can't ask the ledger with the key locked: that's locked, not empty
        sealedLabels.clear()
        locked = true
        assertEquals(LibraryLoad.Locked, ImportedMapLibraryStore(tempDir()).load())
    }

    // ------------------------------------------------------------------ S2 rebuild

    private fun mapFile(dir: File, rel: String, mtime: Long, bytes: Int = 64): File =
        File(dir, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes) { (it + rel.length).toByte() }); setLastModified(mtime) }

    private val georef = PdfGeoreference(
        page = 1,
        crs = GeoCrs.Geographic,
        datum = GeoDatums.WGS84,
        affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
        crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
        origin = GeorefOrigin.ADOBE_VP,
    )

    private fun page(i: Int, g: GeoPdfGeorefResult) = InspectedPage(i, listOf(0.0, 0.0, 600.0, 400.0), null, 0, g)

    @Test
    fun retryOnACorruptLibraryRebuildsFromTheFilesAndDeletesNone() {
        val retry = limits["libraryLoad"]!!.jsonObject["retry"]!!.jsonObject["corrupt"]!!.jsonObject
        assertEquals("rebuild", retry["action"]!!.jsonPrimitive.content)
        assertEquals("map_library_corrupt_message", retry["message"]!!.jsonPrimitive.content)
        val adopted = retry["adoptedName"]!!.jsonObject
        assertEquals("map_recovered_name", adopted["key"]!!.jsonPrimitive.content)
        val firstNumber = adopted["args"]!!.jsonObject["number"]!!.jsonPrimitive.int

        val dir = tempDir()
        val geo = mapFile(dir, "pdf_maps/import-aaaa.pdf", 1_000_000L)
        val pack = mapFile(dir, "mbtiles/import-dddd.mbtiles", 1_500_000L)
        val broken = mapFile(dir, "pdf_maps/import-bbbb.pdf", 2_000_000L)
        val badPack = mapFile(dir, "mbtiles/import-eeee.mbtiles", 2_500_000L)
        val slow = mapFile(dir, "pdf_maps/import-cccc.pdf", 3_000_000L)
        val bake = mapFile(dir, "offline_tiles/tacmap-bake-1234.mbtiles", 500L)
        val busy = mapFile(dir, "pdf_maps/import-ffff.pdf", 600L)
        val partial = mapFile(dir, "pdf_maps/import-gggg.pdf.partial", 700L)
        InFlightImportFiles.register(busy)
        val hashes = mapOf(geo to "a", pack to "d", broken to "b", badPack to "e", slow to "c").mapKeys { it.key.canonicalFile }
            .mapValues { "sha256:" + it.value.repeat(64) }

        val inspected = ArrayList<String>()
        val rebuilt = LibraryRebuild.rebuild(
            filesDir = dir,
            defaultStyle = "OSM_TOPO",
            nowMs = 9L,
            recoveredName = { n -> "Recovered map $n" },
            inspectPdf = { f ->
                inspected += f.name
                when (f.name) {
                    "import-aaaa.pdf" -> InspectionResult.Ok(PdfInspection(2, listOf(
                        page(0, GeoPdfGeorefResult.NoGeoreference),
                        page(1, GeoPdfGeorefResult.Georeferenced(georef, GeorefSelection("viewport", 0, null))),
                    )))
                    "import-bbbb.pdf" -> InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))
                    else -> null // the watchdog fired
                }
            },
            validateMbtiles = { f -> f.name == "import-dddd.mbtiles" },
            hash = { f -> hashes[f.canonicalFile] },
        )

        // bakes, in-flight copies and partials aren't adopted; everything else is, oldest first
        assertEquals(listOf("import-aaaa.pdf", "import-bbbb.pdf", "import-cccc.pdf"), inspected)
        val byFile = rebuilt.entries.associateBy { it.fileName }
        assertEquals(
            listOf("pdf_maps/import-aaaa.pdf", "mbtiles/import-dddd.mbtiles", "pdf_maps/import-bbbb.pdf", "mbtiles/import-eeee.mbtiles", "pdf_maps/import-cccc.pdf"),
            rebuilt.entries.map { it.fileName },
        )
        assertEquals((firstNumber until firstNumber + 5).map { "Recovered map $it" }, rebuilt.entries.map { it.displayName })
        assertEquals(ActiveRef.online("OSM_TOPO"), rebuilt.active)
        rebuilt.entries.forEach { assertEquals(hashes[File(dir, it.fileName).canonicalFile], it.contentKey) }

        val store = ImportedMapLibraryStore(dir)
        fun stateOf(rel: String) = LibraryEntryRules.state(LibraryEntryRules.facts(byFile.getValue(rel)), store.fileStatus(byFile.getValue(rel)))
        // first valid page, no picker, no calibration
        val geoPdf = byFile.getValue("pdf_maps/import-aaaa.pdf").pdf!!
        assertEquals(1, geoPdf.pageIndex)
        assertNotNull(geoPdf.embedded)
        assertNull(geoPdf.manual)
        assertEquals(EntryState.GEO_PDF, stateOf("pdf_maps/import-aaaa.pdf"))
        assertEquals(EntryState.OFFLINE_TILES, stateOf("mbtiles/import-dddd.mbtiles"))
        // wouldn't inspect / timed out / won't open: listed, unavailable, Delete only
        for (rel in listOf("pdf_maps/import-bbbb.pdf", "pdf_maps/import-cccc.pdf", "mbtiles/import-eeee.mbtiles")) {
            assertEquals(rel, EntryState.UNAVAILABLE, stateOf(rel))
            assertEquals(rel, listOf(EntryMenuAction.DELETE), LibraryEntryRules.present(LibraryEntryRules.facts(byFile.getValue(rel)), store.fileStatus(byFile.getValue(rel)), null).menu)
        }
        assertEquals(0, byFile.getValue("pdf_maps/import-bbbb.pdf").pdf!!.pageCount)

        // Recovery provenance survives sealed load and another write; no ownership is guessed.
        val drafts = CalibrationDraftStore(dir)
        assertTrue(drafts.save(CalibrationDraft("sha256:" + "a".repeat(64), 1, "old-id", active = true)))
        assertTrue(drafts.save(CalibrationDraft("sha256:" + "9".repeat(64), 0, "gone-id")))
        assertTrue(store.write(rebuilt))
        val loaded = (store.load() as LibraryLoad.Loaded).state
        assertEquals(true, LibraryMapFiles.reconcile(dir) { loaded })
        assertNull(LibraryMapFiles.sweepBakes(dir) { loaded })
        assertFalse(LibraryRestoreRules.plan(LibraryLoad.Loaded(loaded), LegacyLibraryState.NONE).authoritative)
        if (loaded.permitsCleanup) drafts.prune(loaded.entries.mapNotNull { it.contentKey }.toSet())
        assertTrue(store.write(loaded.copy(active = ActiveRef.online("OSM_STANDARD"))))
        val relaunched = (ImportedMapLibraryStore(dir).load() as LibraryLoad.Loaded).state
        assertFalse(relaunched.permitsCleanup)
        assertEquals(true, LibraryMapFiles.reconcile(dir) { relaunched })
        assertNull(LibraryMapFiles.sweepBakes(dir) { relaunched })
        for (f in listOf(geo, pack, broken, badPack, slow, busy)) assertTrue("${f.name} kept", f.isFile)
        assertTrue("recovery keeps orphan bakes", bake.isFile)
        assertEquals(setOf("sha256:" + "a".repeat(64), "sha256:" + "9".repeat(64)), drafts.all().map { it.contentKey }.toSet())
        assertTrue(partial.exists())
    }

    @Test
    fun aParseThatKilledTheLastRebuildIsNotRetried() {
        val dir = tempDir()
        val pdf = mapFile(dir, "pdf_maps/import-aaaa.pdf", 1_000L)
        File(dir, LibraryRebuild.MARKER_NAME).writeText("pdf_maps/import-aaaa.pdf")
        var asked = false
        val rebuilt = LibraryRebuild.rebuild(
            filesDir = dir, defaultStyle = "OSM_TOPO", nowMs = 1L, recoveredName = { "r$it" },
            inspectPdf = { asked = true; null }, validateMbtiles = { true }, hash = { "sha256:" + "1".repeat(64) },
        )
        assertFalse(asked)
        assertEquals(0, rebuilt.entries.single().pdf!!.pageCount)
        assertTrue(pdf.isFile)
        assertFalse(File(dir, LibraryRebuild.MARKER_NAME).exists())
    }

    @Test
    fun theRebuildWatchdogGivesUpOnAParseThatHangs() {
        val ok = InspectionResult.Ok(PdfInspection(1, emptyList()))
        assertEquals(ok, LibraryRebuild.withWatchdog(1_000) { ok })
        var sawStop = false
        assertNull(LibraryRebuild.withWatchdog(50) { stop ->
            while (!stop()) Thread.sleep(5)
            sawStop = true
            ok
        })
        Thread.sleep(50)
        assertTrue(sawStop)
    }

    // ------------------------------------------------------------------ E3 auto-resume

    @Test
    fun everyAutoResumeRowDecidesTheSameOnAndroid() {
        val rows = limits["lifecycle"]!!.jsonObject["autoResume"]!!.jsonArray.map { it.jsonObject }
        assertTrue("autoResume rows", rows.size >= 12)
        for (row in rows) {
            val id = row["id"]!!.jsonPrimitive.content
            val draft = row["draft"]?.takeUnless { it is JsonNull }?.jsonObject
            val d = AutoResumeRules.decide(
                draftActive = draft?.get("active")?.jsonPrimitive?.boolean,
                points = draft?.get("points")?.jsonPrimitive?.int ?: 0,
                pending = draft?.get("pending")?.jsonPrimitive?.boolean ?: false,
                entry = when (val e = row["entry"]!!.jsonPrimitive.content) {
                    "ok" -> AutoResumeEntry.OK
                    "unavailable" -> AutoResumeEntry.UNAVAILABLE
                    "missing" -> AutoResumeEntry.MISSING
                    else -> error(e)
                },
                library = RestoreStatus.entries.first { it.code == row["library"]!!.jsonPrimitive.content },
                crashSuspectPending = row["crashSuspect"]!!.jsonPrimitive.content != "none",
            )
            val expect = row["expect"]!!.jsonObject
            assertEquals("$id action", expect["action"]!!.jsonPrimitive.content, d.action.code)
            val after = expect["draftAfter"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            assertEquals("$id draftAfter", after, d.draftActiveAfter?.let { if (it) "active" else "inactive" })
            assertEquals("$id frameSheet", expect["frameSheet"]!!.jsonPrimitive.boolean, d.frameSheet)
            assertEquals("$id reopenPending", expect["reopenPending"]!!.jsonPrimitive.boolean, d.reopenPending)
            val toast = expect["toast"]?.takeUnless { it is JsonNull }?.jsonObject
            assertEquals("$id toast", toast?.get("key")?.jsonPrimitive?.content, d.toastPoints?.let { "calibration_resumed" })
            assertEquals("$id toast points", toast?.get("args")?.jsonObject?.get("points")?.jsonPrimitive?.int, d.toastPoints)
        }
    }

    @Test
    fun aPreviewThatCrashedIsTheSuspectAndStartingOnItOpensItAnyway() {
        // C8: the auto-resume preview's token is a launch candidate next to the active PDF's
        val suspectStart = limits["lifecycle"]!!.jsonObject["suspectStart"]!!.jsonObject
        assertTrue(suspectStart["expect"]!!.jsonPrimitive.content.contains("Open Anyway"))
        val file = File(tempDir(), PdfRenderGuard.FILE_NAME)
        val preview = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val active = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        PdfRenderGuard(file).arm(GuardKind.BASE, preview)
        val g = PdfRenderGuard(file)
        assertFalse(g.launchDecided)
        assertTrue(g.launchDecision(listOf(active, preview)).suppress)
        assertTrue(g.isSuspect(preview))
        assertFalse(g.isSuspect(active))
        // the old single-token step would have missed it and dropped the marker
        val again = File(tempDir(), PdfRenderGuard.FILE_NAME)
        PdfRenderGuard(again).arm(GuardKind.BASE, preview)
        assertFalse(PdfRenderGuard(again).launchDecision(active).suppress)
        // startCalibration on the suspect resolves it Open Anyway first (C2)
        g.resolve(GuardResolution.OPEN_ANYWAY)
        assertFalse(g.isSuspect(preview))
        assertFalse(PdfRenderGuard(file).launchDecision(listOf(active, preview)).suppress)
    }

    // ------------------------------------------------------------------ M8 / D6, D7

    @Test
    fun aBackgroundHashMismatchNeverMovesAPdfOffItsSelection() {
        assertEquals(MismatchAction.RECHECK_RENDER, BackgroundHashRules.onMismatch(isPdf = true, shownIsEntry = true, stillActive = true))
        // held back by the crash guard: remember it, Open Anyway draws it and the session fails it then
        assertEquals(MismatchAction.MARK_ONLY, BackgroundHashRules.onMismatch(isPdf = true, shownIsEntry = false, stillActive = true))
        assertEquals(MismatchAction.MARK_ONLY, BackgroundHashRules.onMismatch(isPdf = true, shownIsEntry = false, stillActive = false))
        assertEquals(MismatchAction.ONLINE_AND_ALERT, BackgroundHashRules.onMismatch(isPdf = false, shownIsEntry = true, stillActive = true))
        assertEquals(MismatchAction.ALERT, BackgroundHashRules.onMismatch(isPdf = false, shownIsEntry = false, stillActive = false))
    }

    @Test
    fun aVerifiedTryAgainTakesTheFilesNewStamp() {
        val e = ImportedMapEntry("e", "pdf", "pdf_maps/import-1.pdf", "n", "sha256:" + "1".repeat(64), 10, 20, 1)
        val s = state().copy(entries = listOf(e))
        val ok = LibraryReducer.apply(LibraryTransition.RefreshFileStamp("e", e.contentKey!!, 11, 21), s) as LibraryReduction.Ok
        assertEquals(11L, ok.state.entry("e")!!.byteCount)
        assertEquals(21L, ok.state.entry("e")!!.fileModifiedAtMs)
        // other bytes never get the stamp
        assertEquals(
            LibraryReduction.Rejected(LibraryTransitionError.TARGET_MISMATCH),
            LibraryReducer.apply(LibraryTransition.RefreshFileStamp("e", "sha256:" + "2".repeat(64), 11, 21), s),
        )
    }
}
