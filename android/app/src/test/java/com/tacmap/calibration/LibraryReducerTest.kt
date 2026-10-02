package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.StoredPagePoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract s8.2 commit points: every transition is one candidate state (one sealed
 * write), pinned against import_limits.json lifecycle.libraryWrites. Same cases
 * as the iOS LibraryReducer.
 */
class LibraryReducerTest {
    private val lifecycle = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText())
        .jsonObject["lifecycle"]!!.jsonObject

    private val georef = PdfGeoreferenceCodec.encode(
        PdfGeoreference(
            page = 0, crs = GeoCrs.utm(56, true), datum = GeoDatums.WGS84,
            affine = PlaneAffine(17.6, 0.0, 330_000.0, 0.0, 17.6, 6_240_000.0),
            crop = listOf(PagePoint(0.0, 0.0), PagePoint(1000.0, 0.0), PagePoint(1000.0, 1000.0), PagePoint(0.0, 1000.0)),
            origin = GeorefOrigin.ADOBE_VP,
        )
    )
    private val box = listOf(listOf(0.0, 0.0), listOf(1000.0, 0.0), listOf(1000.0, 1000.0), listOf(0.0, 1000.0))

    private fun pdf(id: String, key: String, embedded: Boolean = false, pages: Int = 1) = ImportedMapEntry(
        id = id, kind = "pdf", fileName = "pdf_maps/import-$id.pdf", displayName = "Sheet $id",
        contentKey = key, byteCount = 10, fileModifiedAtMs = 1, importedAtMs = 1,
        pdf = PdfEntryInfo(pageCount = pages, pageIndex = 0, rotate = 0, pageBox = box, embedded = if (embedded) georef else null),
    )

    private fun tiles(id: String, parent: String? = null) = ImportedMapEntry(
        id = id, kind = "mbtiles", fileName = "offline_tiles/$id.mbtiles", displayName = "Tiles $id",
        byteCount = 10, fileModifiedAtMs = 1, importedAtMs = 1, derivedFromId = parent,
    )

    private fun manual() = ManualCalibration(
        datumId = "WGS84",
        points = listOf(CalibrationPoint("p1", 1, StoredPagePoint(1.0, 2.0), "x", CalibrationReference.Geographic(-33.0, 151.0))),
        georef = georef, n = 4, rmsM = 2.0, grade = "good", savedAtMs = 5,
    )

    private val empty = LibraryState(active = ActiveRef.online("ESRI_SATELLITE"), preferredOnlineStyle = "ESRI_SATELLITE")

    private fun ok(t: LibraryTransition, s: LibraryState): LibraryReduction.Ok {
        val r = LibraryReducer.apply(t, s)
        assertTrue("$t -> $r", r is LibraryReduction.Ok)
        return r as LibraryReduction.Ok
    }

    private fun rejected(t: LibraryTransition, s: LibraryState): LibraryTransitionError {
        val r = LibraryReducer.apply(t, s)
        assertTrue("$t -> $r", r is LibraryReduction.Rejected)
        return (r as LibraryReduction.Rejected).error
    }

    @Test
    fun everyLibraryWriteInTheFixtureHasAReducerCase() {
        val rows = lifecycle["libraryWrites"]!!.jsonArray.map { it.jsonObject["transition"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("select online", "activate entry", "import commit", "commit calibration", "revert to embedded", "change page", "delete"),
            rows,
        )
        val geo = pdf("a", "sha256:a", embedded = true)
        val s = ok(LibraryTransition.AddEntry(geo, activate = true), empty).state
        // select online: active = online(style)
        val online = ok(LibraryTransition.SelectOnline("OSM_TOPO"), s).state
        assertEquals(ActiveRef.online("OSM_TOPO"), online.active)
        assertEquals("OSM_TOPO", online.preferredOnlineStyle)
        assertEquals(1, online.entries.size)
        // activate entry: active = entry(id)
        assertEquals(ActiveRef.entry("a"), ok(LibraryTransition.ActivateEntry("a"), online).state.active)
        // import commit: add entry, plus active if it has a georef or is MBTiles
        val plain = pdf("b", "sha256:b")
        val added = ok(LibraryTransition.AddEntry(plain, activate = false), online).state
        assertEquals(ActiveRef.online("OSM_TOPO"), added.active)
        assertEquals(listOf("a", "b"), added.entries.map { it.id })
        // commit calibration: set manual; active = entry
        val calibrated = ok(LibraryTransition.CommitCalibration("b", manual(), "sha256:b", 0), added).state
        assertEquals(ActiveRef.entry("b"), calibrated.active)
        assertEquals(4, calibrated.entry("b")!!.pdf!!.manual!!.n)
        // revert to embedded: manual = nil (b has none to fall back to, so it can't stay active)
        val reverted = ok(LibraryTransition.RevertToEmbedded("b"), calibrated).state
        assertNull(reverted.entry("b")!!.pdf!!.manual)
        assertEquals(ActiveRef.online("OSM_TOPO"), reverted.active)
        // change page: pageIndex, embedded, issue, manual = nil
        val paged = ok(
            LibraryTransition.ChangePage("b", 2, 90, box, embedded = null, embeddedIssue = "malformed", geometry = null),
            calibrated,
        ).state
        val pb = paged.entry("b")!!.pdf!!
        assertEquals(2, pb.pageIndex)
        assertEquals(90, pb.rotate)
        assertEquals("malformed", pb.embeddedIssue)
        assertNull(pb.manual)
        assertEquals("a page with no georef can't stay the basemap", ActiveRef.online("OSM_TOPO"), paged.active)
        // delete: covered on its own below
    }

    @Test
    fun importingBNeverDropsA() {
        // D5-03: the old store deleted the previous import, the library keeps both
        val a = pdf("a", "sha256:a", embedded = true)
        val b = pdf("b", "sha256:b", embedded = true)
        val s1 = ok(LibraryTransition.AddEntry(a, activate = true), empty).state
        val s2 = ok(LibraryTransition.AddEntry(b, activate = true), s1).state
        assertEquals(listOf("a", "b"), s2.entries.map { it.id })
        assertEquals(ActiveRef.entry("b"), s2.active)
    }

    @Test
    fun deleteTakesItsDerivedTilesAndFallsBackToTheOnlineMap() {
        val a = pdf("a", "sha256:a", embedded = true)
        var s = ok(LibraryTransition.AddEntry(a, activate = true), empty).state
        s = ok(LibraryTransition.AddDerived(tiles("t", parent = "a")), s).state
        s = ok(LibraryTransition.AddEntry(tiles("other"), activate = false), s).state
        assertEquals(ActiveRef.entry("t"), s.active)
        val r = ok(LibraryTransition.DeleteEntry("a"), s)
        assertEquals(setOf("a", "t"), r.removed.map { it.id }.toSet())
        assertEquals(listOf("other"), r.state.entries.map { it.id })
        assertEquals(ActiveRef.online("ESRI_SATELLITE"), r.state.active)
        // deleting something that isn't showing leaves the active one alone
        val kept = ok(LibraryTransition.DeleteEntry("other"), s).state
        assertEquals(ActiveRef.entry("t"), kept.active)
        assertEquals(LibraryTransitionError.UNKNOWN_ENTRY, rejected(LibraryTransition.DeleteEntry("nope"), s))
    }

    @Test
    fun aCalibrationNeverLandsOnAnotherFileOrPage() {
        // D5-10: entryId, contentKey and pageIndex all have to match, else nothing's written
        val s = ok(LibraryTransition.AddEntry(pdf("a", "sha256:a"), activate = false), empty).state
        assertEquals(LibraryTransitionError.TARGET_MISMATCH,
            rejected(LibraryTransition.CommitCalibration("a", manual(), "sha256:different", 0), s))
        assertEquals(LibraryTransitionError.TARGET_MISMATCH,
            rejected(LibraryTransition.CommitCalibration("a", manual(), "sha256:a", 3), s))
        assertEquals(LibraryTransitionError.UNKNOWN_ENTRY,
            rejected(LibraryTransition.CommitCalibration("zz", manual(), "sha256:a", 0), s))
    }

    @Test
    fun onlyGeoreferencedPdfsAndTilesCanBeTheDurableBasemap() {
        // D2-06 / D5-02: an uncalibrated PDF is never the durable active map
        val s = ok(LibraryTransition.AddEntry(pdf("plain", "sha256:p"), activate = false), empty).state
        assertEquals(LibraryTransitionError.NOT_ACTIVATABLE, rejected(LibraryTransition.ActivateEntry("plain"), s))
        val t = ok(LibraryTransition.AddEntry(tiles("t"), activate = false), s).state
        assertEquals(ActiveRef.entry("t"), ok(LibraryTransition.ActivateEntry("t"), t).state.active)
    }

    @Test
    fun theLibraryCapsAtAHundredButABakeStillFits() {
        var s = empty
        repeat(ImportLimits.MAX_LIBRARY_ENTRIES) { s = ok(LibraryTransition.AddEntry(tiles("t$it"), activate = false), s).state }
        assertEquals(LibraryTransitionError.LIBRARY_FULL, rejected(LibraryTransition.AddEntry(tiles("over"), activate = false), s))
        s = ok(LibraryTransition.AddEntry(pdf("p", "sha256:p", embedded = true), activate = false), s.copy(entries = s.entries.drop(1))).state
        // derived tiles of an existing map don't count against the cap
        assertTrue(LibraryReducer.apply(LibraryTransition.AddDerived(tiles("bake", parent = "p")), s) is LibraryReduction.Ok)
    }
}
