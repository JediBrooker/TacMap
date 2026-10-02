package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.StoredPagePoint
import com.tacmap.map.render.pdf.PdfBakePlan
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
 * WP2's bake guarantees on the WP4 library (merge spec M3-M7). Ports of what
 * PdfBakeReconcileInstrumentedTest pinned on the old sealed PDF session: the reconcile the
 * app runs keeps each PDF's bake, Remove deletes it with the PDF missing, a stale/other map
 * attach is sourceChanged, the bake-only sweep keeps named bakes, takes orphans and never
 * runs on a read it can't trust, and a record we'd never have written names nothing. Same
 * LibraryMapFiles + LibraryReducer the view model calls, real files in a temp filesDir.
 */
class LibraryBakeFilesTest {
    private lateinit var files: File

    private val georef = PdfGeoreference(
        page = 0,
        crs = GeoCrs.Geographic,
        datum = GeoDatums.WGS84,
        affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
        crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
        origin = GeorefOrigin.ADOBE_VP,
    )
    private val box = listOf(listOf(0.0, 0.0), listOf(600.0, 0.0), listOf(600.0, 400.0), listOf(0.0, 400.0))
    private val token = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        files = Files.createTempDirectory("library-bake").toFile()
        InFlightImportFiles.clear()
    }

    @After
    fun tearDown() {
        InFlightImportFiles.clear()
        files.deleteRecursively()
    }

    private fun pdfFile(id: String): File =
        File(files, "pdf_maps/import-$id.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }

    private fun entry(id: String, bake: PersistedPdfBake? = null) = ImportedMapEntry(
        id = id, kind = "pdf", fileName = "pdf_maps/import-$id.pdf", displayName = "Sheet $id",
        contentKey = "sha256:$id", byteCount = 4, fileModifiedAtMs = 1, importedAtMs = 1,
        pdf = PdfEntryInfo(
            pageCount = 1, pageIndex = 0, rotate = 0, pageBox = box,
            embedded = PdfGeoreferenceCodec.encode(georef), bake = bake, renderGuardToken = token,
        ),
    )

    private fun state(vararg entries: ImportedMapEntry) =
        LibraryState(active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO", entries = entries.toList())

    private fun plantBake(name: String = "tacmap-bake-${UUID.randomUUID()}.mbtiles"): List<File> {
        val tiles = File(files, "offline_tiles").apply { mkdirs() }
        return listOf("", "-journal", "-wal", "-shm").map { File(tiles, name + it).apply { writeText("x") } }
    }

    private fun record(file: File, tilePx: Int = 256) =
        PersistedPdfBake(file.name, PdfBakePlan.bakeKey(georef.canonicalJson(), tilePx), 0, 12, tilePx, file.length())

    private fun attach(s: LibraryState, id: String, bake: PersistedPdfBake, key: String? = "sha256:$id", tok: String = token) =
        LibraryReducer.apply(LibraryTransition.AttachBake(id, key, tok, bake), s)

    @Test
    fun theAppsReconcileKeepsEachPdfsBakeAndReapsAStaleOne() {
        val pdf = pdfFile("a")
        val bakeFile = plantBake()[0]
        val stale = plantBake()
        val attached = attach(state(entry("a")), "a", record(bakeFile))
        assertTrue("$attached", attached is LibraryReduction.Ok)
        val s = (attached as LibraryReduction.Ok).state

        assertTrue(LibraryMapFiles.reconcile(files, s))
        assertTrue("the PDF stays", pdf.isFile)
        assertTrue("the entry's bake stays with its PDF", bakeFile.isFile)
        stale.forEach { assertFalse("${it.name}: a bake nothing names is reaped", it.exists()) }

        // Remove Offline Tiles: the entry drops it, the next reconcile takes the file
        val cleared = (LibraryReducer.apply(LibraryTransition.ClearBake("a", bakeFile.name), s) as LibraryReduction.Ok).state
        assertNull(cleared.entry("a")!!.pdf!!.bake)
        assertTrue(LibraryMapFiles.reconcile(files, cleared))
        assertTrue(pdf.isFile)
        assertFalse(bakeFile.exists())
    }

    @Test
    fun removeOfflineTilesDeletesTheBakeAndSidecarsWhileThePdfIsMissing() {
        // R2-S2: no PDF on disk, the remove path deletes the bake itself
        val planted = plantBake()
        val s = (attach(state(entry("a")), "a", record(planted[0])) as LibraryReduction.Ok).state
        assertFalse(File(files, "pdf_maps/import-a.pdf").exists())

        // a stale name clears nothing (so nothing gets deleted)
        assertEquals(
            LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED),
            LibraryReducer.apply(LibraryTransition.ClearBake("a", "tacmap-bake-other.mbtiles"), s),
        )
        assertTrue(planted[0].isFile)

        val cleared = LibraryReducer.apply(LibraryTransition.ClearBake("a", planted[0].name), s)
        assertTrue(cleared is LibraryReduction.Ok)
        var letGo = false
        assertTrue(LibraryMapFiles.deleteBake(files, planted[0].name) { letGo = true })
        assertTrue("the live reader lets go before the delete", letGo)
        planted.forEach { assertFalse("${it.name} deleted", it.exists()) }
    }

    @Test
    fun attachBakeIsSourceChangedForAnotherMapTokenBytesOrGeoref() {
        val bakeFile = plantBake()[0]
        val s = state(entry("a"))
        val good = record(bakeFile)
        // R6: a mismatch is sourceChanged, never writeFailed
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), attach(s, "other", good))
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), attach(s, "a", good, tok = UUID.randomUUID().toString()))
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), attach(s, "a", good, key = "sha256:swapped"))
        assertEquals(
            "a bake keyed on another georef",
            LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED),
            attach(s, "a", good.copy(bakeKey = "b".repeat(64))),
        )
        assertEquals(
            "a key for another tile size",
            LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED),
            attach(s, "a", good.copy(tilePx = 512)),
        )
        assertTrue(attach(s, "a", good) is LibraryReduction.Ok)
    }

    @Test
    fun aGeorefChangeDropsTheBake() {
        // M4: commit calibration, revert to embedded and change page all clear it
        val bakeFile = plantBake()[0]
        val s = (attach(state(entry("a")), "a", record(bakeFile)) as LibraryReduction.Ok).state
        val manual = ManualCalibration(
            datumId = "WGS84",
            points = listOf(CalibrationPoint("p1", 1, StoredPagePoint(1.0, 2.0), "x", CalibrationReference.Geographic(-33.0, 151.0))),
            georef = PdfGeoreferenceCodec.encode(georef), n = 4, rmsM = 2.0, grade = "good", savedAtMs = 5,
        )
        val committed = (LibraryReducer.apply(LibraryTransition.CommitCalibration("a", manual, "sha256:a", 0), s) as LibraryReduction.Ok).state
        assertNull(committed.entry("a")!!.pdf!!.bake)
        val withAgain = (attach(committed, "a", record(bakeFile)) as LibraryReduction.Ok).state
        assertNull((LibraryReducer.apply(LibraryTransition.RevertToEmbedded("a"), withAgain) as LibraryReduction.Ok).state.entry("a")!!.pdf!!.bake)
        val paged = LibraryReducer.apply(LibraryTransition.ChangePage("a", 0, 0, box, PdfGeoreferenceCodec.encode(georef), null, null), s)
        assertNull((paged as LibraryReduction.Ok).state.entry("a")!!.pdf!!.bake)
        // and the token survives every one of them, the crash guard keeps knowing the map
        assertEquals(token, committed.entry("a")!!.renderGuardToken)
        assertEquals(token, paged.state.entry("a")!!.renderGuardToken)
    }

    @Test
    fun theBakeSweepKeepsEveryNamedBakeAndTakesOrphansWithThePdfsMissing() {
        val namedA = plantBake()
        val namedB = plantBake()
        val orphan = plantBake()
        val pack = File(files, "offline_tiles/imported-${UUID.randomUUID()}.mbtiles").apply { writeText("pack") }
        val s = state(entry("a", record(namedA[0])), entry("b", record(namedB[0])))
        assertEquals(true, LibraryMapFiles.sweepBakes(files) { s })
        (namedA + namedB).forEach { assertTrue("${it.name}: a named bake stays, sidecars too", it.isFile) }
        orphan.forEach { assertFalse("${it.name} still there", it.exists()) }
        assertTrue("not a bake, not ours to sweep", pack.isFile)
    }

    @Test
    fun removeThenSweepAlsoTakesWhatAnEarlierFailedRemoveLeft() {
        val named = plantBake()
        val leftover = plantBake()
        val s = state(entry("a", record(named[0])))
        val cleared = (LibraryReducer.apply(LibraryTransition.ClearBake("a", named[0].name), s) as LibraryReduction.Ok).state
        assertTrue(LibraryMapFiles.deleteBake(files, named[0].name))
        assertEquals(true, LibraryMapFiles.sweepBakes(files) { cleared })
        (named + leftover).forEach { assertFalse("${it.name} still there", it.exists()) }
    }

    @Test
    fun anEmptyLoadedLibraryIsACleanReadAndEveryBakeGoes() {
        val orphan = plantBake()
        assertEquals(true, LibraryMapFiles.sweepBakes(files) { state() })
        orphan.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun noAuthoritativeReadSkipsTheSweep() {
        // locked / corrupt library, or empty while the legacy stores still wait on migration:
        // the view model hands in no state and nothing is deleted on a guess (F6 on the library)
        val orphan = plantBake()
        assertNull(LibraryMapFiles.sweepBakes(files) { null })
        orphan.forEach { assertTrue("${it.name} deleted on a guess", it.isFile) }
    }

    @Test
    fun aBakeThatsMovedInButNotRecordedYetIsInFlightAndSurvives() {
        // the publish registers the moved-in file until its record lands (M5)
        val publishing = plantBake()
        publishing.forEach(InFlightImportFiles::register)
        assertEquals(true, LibraryMapFiles.sweepBakes(files) { state() })
        assertTrue(LibraryMapFiles.reconcile(files, state()))
        publishing.forEach { assertTrue("${it.name} reaped mid publish", it.isFile) }
    }

    @Test
    fun aBakeRecordWeWouldntHaveWrittenNamesNothing() {
        val bakeFile = plantBake()[0]
        // not a 64 hex key: never written by a real bake, same rule as iOS validBake
        val bad = PersistedPdfBake(bakeFile.name, "k", 0, 12, 256, bakeFile.length())
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.INVALID_BAKE), attach(state(entry("a")), "a", bad))
        // and one that's in there anyway (decoded off disk) is ignored everywhere
        val s = state(entry("a", bad))
        assertNull(s.entry("a")!!.pdf!!.validBake)
        assertTrue("not in the reconcile keep set", ImportedMapLibraryStore(files).managedFiles(s).none { it.name == bakeFile.name })
        assertEquals(true, LibraryMapFiles.sweepBakes(files) { s })
        assertFalse(bakeFile.exists())
    }

    @Test
    fun attachChecksSourceThenRecordThenKeyInTheContractsOrder() {
        // D9 (M3 order): entry, bytes + token, then the record, then the bake key, then the write
        val good = record(plantBake()[0])
        val notOurs = good.copy(fileName = "someones-pack.mbtiles")
        val s = state(entry("a"))
        // a stale source wins over a bad record
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), attach(s, "a", notOurs, key = "sha256:other"))
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), attach(s, "zz", notOurs))
        // right source, a name that isn't tacmap-bake-<id>.mbtiles: invalid (shown as writeFailed)
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.INVALID_BAKE), attach(s, "a", notOurs))
        // a valid record on another georef's key: source changed, not invalid
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), attach(s, "a", good.copy(bakeKey = "0".repeat(64))))
        assertTrue(attach(s, "a", good) is LibraryReduction.Ok)
        // removeBake for a record that isn't the entry's: source changed, nothing written
        val withBake = (attach(s, "a", good) as LibraryReduction.Ok).state
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), LibraryReducer.apply(LibraryTransition.ClearBake("a", "tacmap-bake-other.mbtiles"), withBake))
        assertEquals(LibraryReduction.Rejected(LibraryTransitionError.SOURCE_CHANGED), LibraryReducer.apply(LibraryTransition.ClearBake("nope", good.fileName), withBake))
        // bakeFileNames only ever names our generated form
        assertEquals(setOf(good.fileName), withBake.bakeFileNames)
        assertTrue(state(entry("b", notOurs)).bakeFileNames.isEmpty())
    }

    @Test
    fun anEntryFromBeforeTheMergeUsesItsIdAsTheGuardToken() {
        // M2: random, sealed, never from the file or its name. no lazy write needed
        val old = entry("6f1c2d3e-0000-4000-8000-000000000001").let { it.copy(pdf = it.pdf!!.copy(renderGuardToken = null)) }
        assertEquals(old.id, old.renderGuardToken)
        val junk = old.copy(pdf = old.pdf!!.copy(renderGuardToken = "../not-a-uuid"))
        assertEquals(old.id, junk.renderGuardToken)
        assertEquals(token, entry("x").renderGuardToken)
    }
}
