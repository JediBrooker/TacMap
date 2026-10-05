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
    private val ACTIVE = "active_map_source.json"
    private val RETAINED = "retained_imported_map_source.json"

    private fun state() = LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO")

    // ------------------------------------------------------------------ libraryLoad rows (r1 + 3.0.1 s13.1)

    private fun JsonObject.str(k: String): String = this[k]!!.jsonPrimitive.content
    private fun JsonObject.strOrNull(k: String): String? = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.bool(k: String): Boolean = this[k]!!.jsonPrimitive.boolean
    private fun JsonObject.boolOrNull(k: String): Boolean? = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean

    /** the PDF session prefs. SharedPreferences in the app and there's no JVM stand in for those */
    private class FakeSession : LegacySessionSource {
        /** an active_pdf record that opens comes back as this */
        var record: LegacySession? = null
        /** active_pdf is stored, opening or not */
        var activeStored = false
        /** pdf_calibrations: null none, true opens, false won't */
        var calibrations: Boolean? = null
        var clears = 0
        override fun hasLegacyState() = activeStored || calibrations != null
        override fun hasActivePdf() = activeStored
        override fun legacySession() = record?.takeIf { activeStored }
        override fun activeFile() = record?.file?.takeIf { activeStored }
        override fun calibrationsReadable() = calibrations
        override fun clearAfterLibraryMigration(): Boolean {
            clears++
            activeStored = false
            record = null
            calibrations = null
            return true
        }
    }

    /** a draft store whose every save says no, the given.writes draftFails */
    private class RefusingDrafts(real: CalibrationDraftStorage) : CalibrationDraftStorage by real {
        override fun save(draft: CalibrationDraft) = false
    }

    private val sheet = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400)
    private fun points(n: Int) = (1..n).map { Fiduciary(pdfX = 100.0 * it, pdfY = 50.0 * it, mgrs = "", latitude = -34.0 + it * 0.01, longitude = 150.0) }
    private fun pdfPages(f: File): Int? = if (f.length() >= 4 && String(f.readBytes().copyOf(4)) == "%PDF") 1 else null
    private fun inspect(f: File): InspectionResult =
        if (pdfPages(f) != null) InspectionResult.Ok(PdfInspection(1, listOf(page(0, GeoPdfGeorefResult.NoGeoreference))))
        else InspectionResult.Failed(ImportFailure(ImportError.INVALID_PDF))

    /**
     * one row's device: the library, the old stores and the map files laid out on disk the way
     * the row's given and legacyCodes say, read by the real stores and LegacyMapReader, and a
     * restore pass is what MapViewModel runs: load, the migration hop if it's due, settle, then
     * the cleanup adoptLoaded would do off the sealed library
     */
    private inner class RowDevice(given: JsonObject) {
        val dir = tempDir()
        val store = ImportedMapLibraryStore(dir)
        val selection = ActiveMapSelectionStore.forTests(dir)
        val session = FakeSession()
        val drafts = CalibrationDraftStore(dir)
        val reader = LegacyMapReader(dir, selection, session, pageCount = ::pdfPages, mbtilesName = { null })
        val legacy = given.str("legacy")
        val writes = given.str("writes")
        /** every map file put down for the row. no pass may delete one of them */
        val seeded = ArrayList<File>()
        val migrator = LegacyLibraryMigrator(
            filesDir = dir,
            library = store,
            drafts = if (writes == "draftFails") RefusingDrafts(drafts) else drafts,
            legacy = reader,
            defaultStyle = "OSM_TOPO",
            recoveredName = { "Recovered map $it" },
            inspectPdf = { f -> inspect(f) },
            validateMbtiles = { true },
            // fails before SafeStore sees it, aLibraryWriteThatReallyFails... does a real one
            writeLibrary = if (writes == "libraryFails") ({ LibraryCommit.Failed }) else store::create,
        )

        init {
            sealedLabels.clear()
            locked = false
            val libraryFile = File(dir, libraryName)
            when (val f = given.str("libraryFile")) {
                "ok" -> assertTrue(store.write(state().copy(recoveryPreservesOrphans = given.bool("recoveryPreservesOrphans"))))
                // not a sealed blob and not json either: SafeStore quarantines it
                "unreadable" -> libraryFile.writeBytes(ByteArray(48) { (it * 31).toByte() })
                "newerSchema" -> SafeStore.writeAtomically(
                    libraryFile, ImportedMapLibraryStore.LABEL,
                    """{"schemaVersion":99,"active":{"kind":"online","style":"OSM_TOPO"},"preferredOnlineStyle":"OSM_TOPO","entries":[]}""",
                )
                "absent" -> Unit
                else -> error("libraryFile $f")
            }
            // a write that happened long ago leaves our record behind, nothing else
            if (given.str("libraryFile") == "absent" && given.bool("writtenBefore")) sealedLabels += ImportedMapLibraryStore.LABEL
            if (given.bool("corruptSibling")) File(dir, "$libraryName.corrupt-1700000000000").writeBytes(ByteArray(8))
            layOutLegacy(libraryAbsent = given.str("libraryFile") == "absent")
            if (given.bool("managedFiles")) {
                map("mbtiles/import-aaaaaaaaaaaaaaaa.mbtiles", 3_000L)
                map("pdf_maps/import-bbbbbbbbbbbbbbbb.pdf", 4_000L, pdf = true)
                map("pdf_maps/import-cccccccccccccccc.pdf", 5_000L)
                map("offline_tiles/tacmap-bake-1234.mbtiles", 6_000L)
                map("pdf_maps/import-dddddddddddddddd.pdf.partial", 7_000L)
            }
            locked = given.str("missionKey") == "locked"
        }

        fun map(rel: String, mtime: Long, pdf: Boolean = false): File = File(dir, rel).apply {
            parentFile!!.mkdirs()
            writeBytes(if (pdf) "%PDF-1.4 $rel".toByteArray() else ByteArray(64) { (it + rel.length).toByte() })
            setLastModified(mtime)
            seeded += this
        }

        private fun sealedSelector(name: String, json: String) = SafeStore.writeAtomically(File(dir, name), name, json)

        /** the row's legacy column, built per libraryLoad.legacyCodes */
        private fun layOutLegacy(libraryAbsent: Boolean) {
            val onlineV1 = """{"kind":"ONLINE","preferredOnlineStyle":"OSM_STREET"}"""
            when (legacy) {
                "none" -> Unit
                "readable" -> if (libraryAbsent) {
                    // the old app's active sheet, never georeferenced, two points that never fitted
                    val pdf = map("pdf_maps/import-0123456789abcdef.pdf", 1_000L, pdf = true)
                    session.record = LegacySession(pdf, "Old sheet", sheet, PdfCalibrationIdentity.contentKey(pdf), null, PdfGeorefIssue.NoMetadata, points(2))
                    session.activeStored = true
                    assertTrue(selection.savePdf(BasemapStyle.OSM_TOPO))
                } else {
                    assertTrue(selection.saveOnline(BasemapStyle.OSM_STREET))
                    session.calibrations = true
                }
                // the key goes during the legacy read, see pass()
                "locked" -> assertTrue(selection.saveOnline(BasemapStyle.OSM_STREET))
                // the old app's retained pack is gone, nothing named is left
                "namesNothing" -> sealedSelector(
                    RETAINED, """{"kind":"OFFLINE_TILES","preferredOnlineStyle":"OSM_TOPO","offlineRelativePath":"mbtiles/gone.mbtiles"}""",
                )
                "corrupt" -> File(dir, ACTIVE).writeText("{not a selector")
                "quarantinedOnly" -> File(dir, "$ACTIVE.corrupt-1700000000000").writeText("bit rot")
                "retainedCorrupt" -> {
                    sealedSelector(ACTIVE, onlineV1)
                    map("mbtiles/import-eeeeeeeeeeeeeeee.mbtiles", 2_000L)
                    File(dir, RETAINED).writeText("{not a selector")
                }
                "retainedQuarantinedOnly" -> {
                    sealedSelector(ACTIVE, onlineV1)
                    map("mbtiles/import-eeeeeeeeeeeeeeee.mbtiles", 2_000L)
                    File(dir, "$RETAINED.corrupt-1700000000000").writeText("bit rot")
                }
                // stored, won't open with the key there
                "sessionInvalid" -> {
                    map("pdf_maps/import-0123456789abcdef.pdf", 1_000L, pdf = true)
                    session.activeStored = true
                }
                "pdfHashMismatch" -> {
                    val pdf = map("pdf_maps/import-0123456789abcdef.pdf", 1_000L, pdf = true)
                    session.record = LegacySession(pdf, "Old sheet", sheet, "sha256:" + "0".repeat(64), null, PdfGeorefIssue.NoMetadata, points(2))
                    session.activeStored = true
                }
                "pdfUnconvertible" -> {
                    val pdf = map("pdf_maps/import-0123456789abcdef.pdf", 1_000L)
                    session.record = LegacySession(pdf, "Old sheet", sheet, PdfCalibrationIdentity.contentKey(pdf), null, PdfGeorefIssue.NoMetadata)
                    session.activeStored = true
                }
                else -> error("legacy $legacy")
            }
        }

        inner class Seen(val plan: RestorePlan, val cleared: Boolean)

        fun pass(): Seen {
            val clearsBefore = session.clears
            val copiesBefore = quarantineCopies()
            val first = store.load()
            val hop = first == LibraryLoad.Empty && migrator.isDue()
            val migrated = if (hop) {
                // legacy locked: the key goes right in the middle of the legacy read
                if (legacy == "locked") locked = true
                try {
                    migrator.migrate()
                } finally {
                    if (legacy == "locked") locked = false
                }
            } else {
                null
            }
            val plan = migrator.settle(if (hop) store.load() else first, migrated)
            if (plan.usable) cleanup()
            seeded.forEach { assertTrue("${it.parentFile!!.name}/${it.name} deleted", it.isFile) }
            copiesBefore.forEach { assertTrue("$it cleared", File(dir, it).isFile) }
            return Seen(plan, session.clears > clearsBefore)
        }

        /** adoptLoaded's reconcile + bake sweep, off the sealed library the way durableForCleanup reads it */
        private fun cleanup() {
            val durable = when (val l = store.load()) {
                is LibraryLoad.Loaded -> l.state
                LibraryLoad.Empty -> state().takeIf { !migrator.isDue() }
                else -> null
            }
            LibraryMapFiles.reconcile(dir, pruneDrafts = { drafts.prune(it) }) { durable }
            LibraryMapFiles.sweepBakes(dir) { durable }
        }

        fun quarantineCopies(): List<String> = dir.list().orEmpty().filter { ".corrupt-" in it }
    }

    private fun assertPass(id: String, expect: JsonObject, seen: RowDevice.Seen) {
        val p = seen.plan
        assertEquals("$id status", expect.str("status"), p.status.code)
        assertEquals("$id migration", expect.str("migration"), p.migration.code)
        for (k in listOf("reconcile", "bakeSweep", "draftPrune")) {
            if (k in expect) assertEquals("$id $k", expect.bool(k), p.authoritative)
        }
        assertEquals("$id clearLegacy", expect.bool("clearLegacy"), seen.cleared)
        assertEquals("$id issue", expect.strOrNull("issue"), p.issue?.code)
        assertEquals("$id notice", expect.strOrNull("notice"), p.notice?.code)
        assertEquals("$id recoveryPreservesOrphans", expect.boolOrNull("recoveryPreservesOrphans"), p.recoveryPreservesOrphans)
        assertEquals("$id importAllowed", expect.bool("importAllowed"), p.usable)
        if ("basemapChangeAllowed" in expect) assertEquals("$id basemapChangeAllowed", expect.bool("basemapChangeAllowed"), p.usable)
    }

    @Test
    fun everyLibraryLoadRowRestoresTheSameWayOnAndroid() {
        val section = limits["libraryLoad"]!!.jsonObject
        val rows = section["rows"]!!.jsonArray.map { it.jsonObject }
        val android = rows.filter { r -> r["platforms"]!!.jsonArray.any { it.jsonPrimitive.content == "android" } }
        assertEquals("libraryLoad rows", 28, rows.size)
        assertEquals("android rows", 27, android.size)
        // every legacy code an android row uses is one the reader can come back with
        val codes = section["legacyCodes"]!!.jsonObject.keys
        android.forEach { assertTrue(it.str("id"), it["given"]!!.jsonObject.str("legacy") in codes) }
        for (row in android) {
            val id = row.str("id")
            val expect = row["expect"]!!.jsonObject
            val device = RowDevice(row["given"]!!.jsonObject)
            assertPass(id, expect, device.pass())
            if (expect.str("migration") == "salvage" || expect.str("migration") == "adoptOrphans") {
                // every map file in our dirs is listed once (bakes and .partial residue aren't maps)
                val library = (device.store.load() as LibraryLoad.Loaded).state
                assertEquals("$id flag", true, library.recoveryPreservesOrphans)
                val maps = device.seeded.filter { it.name.endsWith(".pdf") || it.name.endsWith(".mbtiles") }
                    .filterNot { ManagedImportedMapFileLifecycle.isGeneratedBakeName(it.name) }
                    .map { "${it.parentFile!!.name}/${it.name}" }.sorted()
                assertEquals("$id entries", maps, library.entries.map { it.fileName }.sorted())
                // the old calibration's for other bytes, or nothing opened: none is applied
                assertTrue("$id calibration", library.entries.none { it.pdf?.manual != null })
            }
            // nextRestore: Retry, an unlock or the next launch, on whatever this pass left
            assertPass("$id nextRestore", expect["nextRestore"]!!.jsonObject, device.pass())
        }
    }

    private fun given(legacy: String, files: Boolean = true) = Json.parseToJsonElement(
        """{"libraryFile":"absent","missionKey":"unlocked","corruptSibling":false,"writtenBefore":false,"legacy":"$legacy","recoveryPreservesOrphans":false,"managedFiles":$files,"writes":"ok"}""",
    ).jsonObject

    @Test
    fun aSalvageConvertsWhatStillReadsAdoptsTheRestAndFreezesTheOldStores() {
        // wp4-android-1 with the PDF session still there: the selector won't read, the session does
        val device = RowDevice(given("corrupt"))
        val pdf = device.map("pdf_maps/import-0123456789abcdef.pdf", 1_000L, pdf = true)
        device.session.record = LegacySession(pdf, "Old sheet", sheet, PdfCalibrationIdentity.contentKey(pdf), null, PdfGeorefIssue.NoMetadata, points(2))
        device.session.activeStored = true

        val seen = device.pass()
        assertEquals(RestoreMigration.SALVAGE, seen.plan.migration)
        assertEquals(RestoreNotice.RECOVERED, seen.plan.notice)
        assertFalse(seen.cleared)
        val library = (device.store.load() as LibraryLoad.Loaded).state
        assertEquals(true, library.recoveryPreservesOrphans)
        // what converts keeps its name and its points (as a draft, they never fitted)
        val old = library.entries.single { it.fileName == "pdf_maps/import-0123456789abcdef.pdf" }
        assertEquals("Old sheet", old.displayName)
        assertEquals(2, device.drafts.load("${old.contentKey}#0")!!.points.size)
        // everything else is adopted once, oldest first. bakes and .partial residue aren't maps
        assertEquals(
            listOf("mbtiles/import-aaaaaaaaaaaaaaaa.mbtiles", "pdf_maps/import-bbbbbbbbbbbbbbbb.pdf", "pdf_maps/import-cccccccccccccccc.pdf"),
            library.entries.filter { it.id != old.id }.map { it.fileName },
        )
        assertEquals(listOf("Recovered map 1", "Recovered map 2", "Recovered map 3"), library.entries.filter { it.id != old.id }.map { it.displayName })
        // a never georeferenced sheet isn't a basemap, and with the selector unreadable the default style's up
        assertEquals(ActiveRef.online("OSM_TOPO"), library.active)
        // frozen, never cleared: the session record and the selector's quarantine copy
        assertTrue(device.session.activeStored)
        assertTrue(device.quarantineCopies().any { it.startsWith("$ACTIVE.corrupt-") })

        // the launch after: an ordinary flagged library, nothing migrates or gets cleaned up again
        val next = device.pass()
        assertEquals(RestoreMigration.NONE, next.plan.migration)
        assertFalse(next.plan.authoritative)
        assertFalse(next.cleared)
        assertEquals(null, next.plan.notice)
    }

    @Test
    fun aMigrationDraftThatFailsToSaveWritesNothingAndClearsNothingTillItSaves() {
        // wp4-android-8: the old order wrote the library, ignored the save and cleared the prefs
        val device = RowDevice(given("readable", files = false))
        // the drafts file can't be replaced (SafeStore's temp file is a directory), a real write failure
        val jam = File(device.dir, CalibrationDraftStore.FILE_NAME + ".tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        val blocked = device.pass()
        assertEquals(RestoreStatus.MIGRATION_PENDING, blocked.plan.status)
        assertEquals(RestoreIssue.LOCKED_RETRY, blocked.plan.issue)
        assertEquals(LibraryLoad.Empty, device.store.load())
        assertFalse(blocked.cleared)
        assertTrue(device.session.activeStored)
        assertTrue(device.selection.hasClearableLegacyState())

        // Retry once the disk takes it: drafts, then the library, then the old stores go
        jam.deleteRecursively()
        val retried = device.pass()
        assertEquals(RestoreMigration.RUN, retried.plan.migration)
        assertTrue(retried.cleared)
        val library = (device.store.load() as LibraryLoad.Loaded).state
        assertEquals(2, device.drafts.all().single { it.entryId == library.entries.single().id }.points.size)
    }

    @Test
    fun aLibraryWriteThatReallyFailsNeverDeletesAnythingAndRetryGetsEveryFileBack() {
        // SafeStore puts the sealed-only record down before the bytes, so after a real failure
        // (full disk) the library reads as written before and gone: corrupt, where the fixture's
        // seam says pending. Both are safe, this pins that: no pass deletes a file (pass() checks
        // every one), cleans up or clears, and the corrupt Retry (S2 rebuild) brings every map back
        val device = RowDevice(given("corrupt"))
        val jam = File(device.dir, "$libraryName.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        repeat(2) {
            val seen = device.pass()
            assertFalse(seen.plan.authoritative)
            assertFalse(seen.cleared)
            assertTrue(seen.plan.status.toString(), seen.plan.status == RestoreStatus.MIGRATION_PENDING || seen.plan.status == RestoreStatus.CORRUPT)
        }
        jam.deleteRecursively()
        val rebuilt = LibraryRebuild.rebuild(
            filesDir = device.dir, defaultStyle = "OSM_TOPO", nowMs = 1L, recoveredName = { "Recovered map $it" },
            inspectPdf = { f -> inspect(f) }, validateMbtiles = { true },
        )
        assertTrue(device.store.create(rebuilt) is LibraryCommit.Written)
        assertEquals(
            listOf("mbtiles/import-aaaaaaaaaaaaaaaa.mbtiles", "pdf_maps/import-bbbbbbbbbbbbbbbb.pdf", "pdf_maps/import-cccccccccccccccc.pdf"),
            (device.store.load() as LibraryLoad.Loaded).state.entries.map { it.fileName },
        )
        device.pass()
    }

    @Test
    fun managedFilesAreWhatACleanupWouldDeleteThatNobodysWriting() {
        val dir = tempDir()
        assertFalse(LibraryRebuild.hasManagedFiles(dir, emptySet()))
        File(dir, "pdf_maps").mkdirs()
        File(dir, "pdf_maps/notes.txt").writeText("x")
        // a directory named like a map isn't a file the reconcile would touch
        File(dir, "offline_tiles/nested.mbtiles").mkdirs()
        assertFalse(LibraryRebuild.hasManagedFiles(dir, emptySet()))
        for (rel in listOf("pdf_maps/import-1.pdf", "pdf_maps/import-2.pdf.partial", "mbtiles/import-3.mbtiles-wal", "offline_tiles/tacmap-bake-4.mbtiles", "mbtiles/import-5.mbtiles")) {
            val f = File(dir, rel).apply { parentFile!!.mkdirs(); writeText("x") }
            assertTrue(rel, LibraryRebuild.hasManagedFiles(dir, emptySet()))
            // in flight, or the copy a stuck s9.8 marker names: not counted
            assertFalse(rel, LibraryRebuild.hasManagedFiles(dir, setOf(f)))
            f.delete()
        }
    }

    @Test
    fun theStoreHalfOfS1TheSecondLoadAfterAQuarantineIsNotEmpty() {
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
