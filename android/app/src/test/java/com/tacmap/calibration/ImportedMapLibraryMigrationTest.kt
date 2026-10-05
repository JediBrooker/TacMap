package com.tacmap.calibration

import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.d
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.WGS84_OVERRIDE
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract s8.2 one-time migration off the old selector + PDF session, from
 * frozen legacy inputs. Pure, the store reading lives in LegacyMapReader.
 */
class ImportedMapLibraryMigrationTest {
    private val stored = PdfGeorefFixture.root.obj("fiduciaryFits").arr("storedSets").map { it.jsonObject }
        .first { it.str("name") == "stored_zone55_typed_east_of_150" }

    private fun dir(): File = Files.createTempDirectory("migrate").toFile()

    private fun file(dir: File, rel: String) = File(dir, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(32) { 7 }) }

    private fun geometry(side: Double) = PdfPageGeometry(PdfBox(0.0, 0.0, side, side), null, 0, side.toInt(), side.toInt())

    private fun storedFids(): List<Fiduciary> = stored.arr("fiduciaries").map { it.jsonObject }.map {
        Fiduciary(pdfX = it.d("pdfX"), pdfY = it.d("pdfY"), mgrs = it.str("mgrs"), latitude = it.d("latitude"), longitude = it.d("longitude"))
    }

    private fun legacyPdf(dir: File, fids: List<Fiduciary> = emptyList(), embedded: PdfGeoreference? = null, pending: List<Fiduciary> = emptyList()) =
        LegacyPdf(
            file = file(dir, "pdf_maps/import-0123456789abcdef.pdf"),
            displayName = "Bathurst 1:25k",
            geometry = geometry(2411.7165354),
            pageCount = 1,
            contentKey = "sha256:" + "aa".repeat(32),
            embedded = embedded,
            manualFiduciaries = fids,
            pendingFiduciaries = pending,
        )

    private var ids = 0
    private fun id() = "id-${ids++}"

    @Test
    fun aNeverGeoreferencedActivePdfIsKeptButNotTheBasemapAndTheUserIsTold() {
        // the old camera-fallback placement: needsCalibration, never active (D2-06, D5-02)
        val d = dir()
        val r = requireNotNull(ImportedMapLibraryMigration.build(
            LegacyMapInputs(LegacyActive.Pdf, "OSM_TOPO", legacyPdf(d), emptyList()), d, 9L, ::id,
        ))
        assertEquals(ActiveRef.online("OSM_TOPO"), r.state.active)
        assertEquals("Bathurst 1:25k", r.uncalibratedActiveName)
        val e = r.state.entries.single()
        assertEquals(EntryState.NEEDS_CALIBRATION, LibraryEntryRules.state(LibraryEntryRules.facts(e), EntryFileStatus.OK))
        assertEquals("pdf_maps/import-0123456789abcdef.pdf", e.fileName)
    }

    @Test
    fun v1FiduciariesBecomeAManualCalibrationWithTheWgs84Override() {
        val d = dir()
        val r = requireNotNull(ImportedMapLibraryMigration.build(
            LegacyMapInputs(LegacyActive.Pdf, "OSM_TOPO", legacyPdf(d, fids = storedFids()), emptyList()), d, 9L, ::id,
        ))
        val e = r.state.entries.single()
        assertEquals(ActiveRef.entry(e.id), r.state.active)
        assertNull(r.uncalibratedActiveName)
        val manual = requireNotNull(e.pdf!!.manual)
        assertEquals("WGS84", manual.datumId)
        assertEquals(4, manual.n)
        assertEquals("good", manual.grade)
        manual.points.forEach {
            assertEquals(WGS84_OVERRIDE, it.datumOverride)
            assertTrue(it.reference is CalibrationReference.Geographic)
        }
        // the stored MGRS text is what the user sees in the list
        assertEquals("55HGC 92000 52000", manual.points.first().input)
        assertEquals(listOf(1, 2, 3, 4), manual.points.map { it.number })
        // refit through WP1: lands the printed grid like the shared fixture says
        val g = requireNotNull(PdfGeoreferenceCodec.decode(manual.georef))
        val want = stored.obj("expected")
        assertEquals(55, (g.crs as GeoCrs.TransverseMercator).utmZone)
        val p = g.planeOf(2112.9448819, 298.7716535)
        val plane = want.arr("planePoints")[0].let(PdfGeorefFixture::doubles)
        assertEquals(plane[0], p.x, 0.01)
        assertEquals(plane[1], p.y, 0.01)
        assertTrue(r.drafts.isEmpty())
    }

    @Test
    fun pointsThatCouldNotFitArentThrownAwayTheyBecomeADraft() {
        val d = dir()
        val two = storedFids().take(2)
        val r = requireNotNull(ImportedMapLibraryMigration.build(
            LegacyMapInputs(LegacyActive.None, "OSM_TOPO", legacyPdf(d, pending = two), emptyList()), d, 9L, ::id,
        ))
        val draft = r.drafts.single()
        assertEquals(r.state.entries.single().id, draft.entryId)
        assertEquals("sha256:" + "aa".repeat(32) + "#0", draft.key)
        assertEquals(2, draft.points.size)
        assertEquals(3, draft.nextNumber)
        assertEquals(false, draft.active)
    }

    @Test
    fun aGeoPdfStaysTheBasemapAndRetainedTilesComeAlong() {
        val d = dir()
        val geo = PdfGeoreference(
            page = 0, crs = GeoCrs.utm(55, true), datum = GeoDatums.WGS84,
            affine = PlaneAffine(8.8, 0.0, 773_365.0, 0.0, 8.8, 6_249_366.0),
            crop = geometry(2411.7165354).visibleBox.corners(), origin = GeorefOrigin.ADOBE_VP,
        )
        val tiles = file(d, "mbtiles/import-fedcba9876543210.mbtiles")
        val r = requireNotNull(ImportedMapLibraryMigration.build(
            LegacyMapInputs(LegacyActive.Pdf, "ESRI_SATELLITE", legacyPdf(d, embedded = geo), listOf(LegacyOffline(tiles, "Tiles"))),
            d, 9L, ::id,
        ))
        assertEquals(2, r.state.entries.size)
        val pdf = r.state.entries.first { it.isPdf }
        assertNotNull(pdf.pdf!!.embedded)
        assertEquals(ActiveRef.entry(pdf.id), r.state.active)
        assertEquals("ESRI_SATELLITE", r.state.preferredOnlineStyle)
        // and an active retained MBTiles stays active
        val r2 = requireNotNull(ImportedMapLibraryMigration.build(
            LegacyMapInputs(LegacyActive.Offline(tiles), "OSM_TOPO", null, listOf(LegacyOffline(tiles, "Tiles"))), d, 9L, ::id,
        ))
        assertEquals(ActiveRef.entry(r2.state.entries.single().id), r2.state.active)
    }

    @Test
    fun aFileOutsideTheManagedDirectoriesIsLeftOutAndListedNeverDropped() {
        // 3.0.1: the rest still converts, the stray file is named so the migration salvages
        // (old stores frozen) instead of writing a library that silently lost it
        val d = dir()
        val stray = LegacyPdf(
            file = file(d, "elsewhere/map.pdf"), displayName = "x", geometry = geometry(100.0), pageCount = 1,
            contentKey = "sha256:" + "bb".repeat(32),
        )
        val strayPack = file(d, "elsewhere/tiles.mbtiles")
        val tiles = file(d, "mbtiles/import-fedcba9876543210.mbtiles")
        val r = ImportedMapLibraryMigration.build(
            LegacyMapInputs(LegacyActive.Pdf, "OSM_TOPO", stray, listOf(LegacyOffline(strayPack, "Stray"), LegacyOffline(tiles, "Tiles"))),
            d, 9L, ::id,
        )
        assertEquals(listOf(stray.file, strayPack), r.unconverted)
        assertEquals(listOf("mbtiles/import-fedcba9876543210.mbtiles"), r.state.entries.map { it.fileName })
        assertEquals(ActiveRef.online("OSM_TOPO"), r.state.active)
        assertNull(r.uncalibratedActiveName)
    }
}
