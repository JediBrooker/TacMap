package com.tacmap.calibration

import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plan 02 s1 persistence: schema 1 sessions (page -> lon/lat affine) migrate to
 * the georef model, and schema 2 round trips through the sealed JSON.
 */
class PdfSessionMigrationTest {
    private val sheets = PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }.associateBy { it.str("id") }

    private fun built(id: String): GeoPdfGeorefResult {
        val sheet = sheets.getValue(id)
        return GeoPdfGeoreferencer.build(PdfGeorefFixture.pageData(sheet, sheet.obj("written")))
    }

    private fun geometry(sheet: JsonObject, w: Int, h: Int): PdfPageGeometry {
        val media = PdfGeorefFixture.doubles(sheet["mediaBox"]!!)
        return PdfPageGeometry(PdfBox.of(media)!!, null, 0, w, h)
    }

    private val noReparse: () -> GeoPdfGeorefResult? = { fail("must not re-parse a hand calibrated sheet"); null }

    @Test
    fun v1GeoPdfSessionsAreReparsedNotTrusted() {
        val g = geometry(sheets.getValue("sf_iso"), 824, 1051)
        // old parsed calibration: the stored lon/lat affine is ignored, the file is re-read
        val parsed = PdfSessionMigration.migrate("parsed", emptyList(), 824, 1051, g) { built("sf_iso") }
        val ok = parsed as PdfSessionMigration.Outcome.Georeferenced
        assertTrue(ok.calibration is Calibration.Parsed)
        assertEquals(GeorefOrigin.ADOBE_VP, ok.calibration.georef.origin)
        // auto correspondences were stored as fiduciaries with a blank MGRS: same thing
        val auto = List(4) { Fiduciary(pdfX = 1.0 * it, pdfY = 2.0 * it, mgrs = "", latitude = 37.7, longitude = -122.4) }
        val again = PdfSessionMigration.migrate("fiduciaries", auto, 824, 1051, g) { built("sf_lgictm") }
        assertEquals(GeorefOrigin.LGI_DICT, (again as PdfSessionMigration.Outcome.Georeferenced).calibration.georef.origin)
        // a GeoPDF whose re-parse now gets rejected says so instead of keeping the old box
        val rejected = PdfSessionMigration.migrate("parsed", emptyList(), 824, 1051, g) {
            GeoPdfGeorefResult.Rejected(GeorefRejectReason.UNKNOWN_DATUM)
        }
        assertEquals(
            PdfGeorefIssue.Rejected(GeorefRejectReason.UNKNOWN_DATUM),
            (rejected as PdfSessionMigration.Outcome.Uncalibrated).issue,
        )
    }

    @Test
    fun v1HandCalibrationIsRefittedInUtmFromRendererSpacePoints() {
        val sheet = sheets.getValue("sf_plain")
        val truth = sheet.obj("truth")
        val media = PdfGeorefFixture.doubles(sheet["mediaBox"]!!)
        val wi = 824
        val hi = 1051
        val g = geometry(sheet, wi, hi)
        val truthGeoref = requireNotNull(
            FiduciaryFitter.fit(
                truth.arr("fiducialTargets").map { it.jsonObject }.map {
                    FiduciaryPoint(PdfGeorefFixture.point(it["page"]!!), FiduciaryReferenceParser.parse(it.str("label"))!!)
                },
                GeoDatums.WGS84, media.toDoubleArray(),
            )?.georeference(g.visibleCrop())
        )
        // what v1 saved: ratio * int page size (y up) and the WGS84 lat/lon of the typed MGRS
        val v1 = truth.arr("fiducialTargets").map { it.jsonObject }.map {
            val p = PdfGeorefFixture.point(it["page"]!!)
            val w = requireNotNull(truthGeoref.toWGS84(p.x, p.y))
            Fiduciary(
                pdfX = p.x / media[2] * wi,
                pdfY = p.y / media[3] * hi,
                mgrs = it.str("label"),
                latitude = w.latitude,
                longitude = w.longitude,
            )
        }
        val outcome = PdfSessionMigration.migrate("fiduciaries", v1, wi, hi, g, noReparse)
        val cal = (outcome as PdfSessionMigration.Outcome.Georeferenced).calibration as Calibration.Fiduciaries
        assertEquals(GeorefOrigin.FIDUCIARIES, cal.georef.origin)
        assertEquals(10, (cal.georef.crs as GeoCrs.TransverseMercator).utmZone)
        // fiduciaries come back in raw user space
        v1.zip(cal.fids).forEach { (old, migrated) ->
            assertEquals(old.pdfX * media[2] / wi, migrated.pdfX, 1e-9)
            assertEquals(old.pdfY * media[3] / hi, migrated.pdfY, 1e-9)
        }
        // and the refit lands on the printed grid
        for (c in sheet.arr("gridChecks").map { it.jsonObject }) {
            val p = PdfGeorefFixture.point(c["page"]!!)
            val w = PdfGeorefFixture.doubles(c["wgs84"]!!)
            val got = requireNotNull(cal.georef.toWGS84(p.x, p.y))
            assertTrue(PdfGeorefFixture.metres(w[0], w[1], got.latitude, got.longitude) < 0.05)
        }
    }

    @Test
    fun v1CameraFallbackAndBrokenCalibrationsBecomeUncalibrated() {
        val g = PdfPageGeometry.rendererOnly(600, 400)
        assertEquals(
            PdfGeorefIssue.LegacyPlacement,
            (PdfSessionMigration.migrate("none", emptyList(), 600, 400, g, noReparse) as PdfSessionMigration.Outcome.Uncalibrated).issue,
        )
        val two = List(2) { Fiduciary(pdfX = 10.0 * it, pdfY = 5.0, mgrs = "10SEG4700077000", latitude = 37.7, longitude = -122.4) }
        assertEquals(
            PdfGeorefIssue.LegacyPlacement,
            (PdfSessionMigration.migrate("fiduciaries", two, 600, 400, g, noReparse) as PdfSessionMigration.Outcome.Uncalibrated).issue,
        )
        // collinear under the new 0.02 rule: keep the points for the next attempt
        val line = listOf(100.0 to 50.0, 300.0 to 51.0, 500.0 to 50.0).map { (x, y) ->
            Fiduciary(pdfX = x, pdfY = y, mgrs = "10SEG4700077000", latitude = 37.73, longitude = -122.4 + x * 1e-4)
        }
        val lost = PdfSessionMigration.migrate("fiduciaries", line, 600, 400, g, noReparse) as PdfSessionMigration.Outcome.Uncalibrated
        assertEquals(PdfGeorefIssue.CalibrationLost, lost.issue)
        assertEquals(3, lost.pendingFiduciaries.size)
    }

    @Test
    fun v2SessionWhoseGeorefNoLongerDecodesIsReparsedOrRefitNotDropped() {
        // a GeoPDF goes back to its bytes, same as a v1 one
        val sheet = sheets.getValue("sf_iso")
        val g = geometry(sheet, 824, 1051)
        val parsed = PdfSessionMigration.recover("parsed", emptyList(), g) { built("sf_iso") }
        assertEquals(GeorefOrigin.ADOBE_VP, (parsed as PdfSessionMigration.Outcome.Georeferenced).calibration.georef.origin)
        val nowRejected = PdfSessionMigration.recover("parsed", emptyList(), g) { GeoPdfGeorefResult.Rejected(GeorefRejectReason.RMS_GATE) }
        assertEquals(PdfGeorefIssue.Rejected(GeorefRejectReason.RMS_GATE), (nowRejected as PdfSessionMigration.Outcome.Uncalibrated).issue)

        // hand calibration: v2 points are raw already, so they refit as is (no v1 page space undo)
        val plain = sheets.getValue("sf_plain")
        val truth = plain.obj("truth")
        val media = PdfGeorefFixture.doubles(plain["mediaBox"]!!)
        val pg = geometry(plain, 824, 1051)
        val truthGeoref = requireNotNull(
            FiduciaryFitter.fit(
                truth.arr("fiducialTargets").map { it.jsonObject }.map {
                    FiduciaryPoint(PdfGeorefFixture.point(it["page"]!!), FiduciaryReferenceParser.parse(it.str("label"))!!)
                },
                GeoDatums.WGS84, media.toDoubleArray(),
            )?.georeference(pg.visibleCrop())
        )
        val raw = truth.arr("fiducialTargets").map { it.jsonObject }.map {
            val p = PdfGeorefFixture.point(it["page"]!!)
            val w = requireNotNull(truthGeoref.toWGS84(p.x, p.y))
            Fiduciary(pdfX = p.x, pdfY = p.y, mgrs = it.str("label"), latitude = w.latitude, longitude = w.longitude)
        }
        val refit = PdfSessionMigration.recover("fiduciaries", raw, pg, noReparse) as PdfSessionMigration.Outcome.Georeferenced
        val cal = refit.calibration as Calibration.Fiduciaries
        assertEquals(raw, cal.fids)
        for (c in plain.arr("gridChecks").map { it.jsonObject }) {
            val p = PdfGeorefFixture.point(c["page"]!!)
            val w = PdfGeorefFixture.doubles(c["wgs84"]!!)
            val got = requireNotNull(cal.georef.toWGS84(p.x, p.y))
            assertTrue(PdfGeorefFixture.metres(w[0], w[1], got.latitude, got.longitude) < 0.05)
        }

        // refused refit (collinear) keeps the points pending, same as iOS now
        val line = listOf(100.0 to 50.0, 300.0 to 51.0, 500.0 to 50.0).map { (x, y) ->
            Fiduciary(pdfX = x, pdfY = y, mgrs = "10SEG4700077000", latitude = 37.73, longitude = -122.4 + x * 1e-4)
        }
        val lost = PdfSessionMigration.recover("fiduciaries", line, pg, noReparse) as PdfSessionMigration.Outcome.Uncalibrated
        assertEquals(PdfGeorefIssue.CalibrationLost, lost.issue)
        assertEquals(line, lost.pendingFiduciaries)
        // and nothing to refit from is just lost, nothing parsed
        val none = PdfSessionMigration.recover("fiduciaries", line.take(2), pg, noReparse) as PdfSessionMigration.Outcome.Uncalibrated
        assertEquals(PdfGeorefIssue.CalibrationLost, none.issue)
    }

    @Test
    fun v1PointsMoveOutOfTheCropOriginRendererFrame() {
        val offset = PdfPageGeometry(
            PdfBox(100.0, 150.0, 924.3149606, 1201.0866142),
            PdfBox(120.0, 170.0, 904.3149606, 1181.0866142),
            0, 784, 1011,
        )
        val p = requireNotNull(PdfSessionMigration.v1PointToRaw(0.0, 0.0, 784, 1011, offset))
        assertEquals(120.0, p.x, 1e-9)
        assertEquals(170.0, p.y, 1e-9)
        val q = requireNotNull(PdfSessionMigration.v1PointToRaw(784.0, 1011.0, 784, 1011, offset))
        assertEquals(904.3149606, q.x, 1e-9)
        assertEquals(1181.0866142, q.y, 1e-9)
        assertNull(PdfSessionMigration.v1PointToRaw(Double.NaN, 0.0, 784, 1011, offset))
    }

    @Test
    fun georefsRoundTripThroughTheSealedJson() {
        val json = Json { ignoreUnknownKeys = true }
        for (id in listOf("sf_iso", "lcc_lgile", "geog_iso", "sf27_iso", "hols_epsg", "usgs_sf_north", "rot90_iso")) {
            val sheet = sheets.getValue(id)
            val written = sheet["written"]?.jsonObject ?: sheet
            val georef = (GeoPdfGeoreferencer.build(PdfGeorefFixture.pageData(sheet, written)) as GeoPdfGeorefResult.Georeferenced).georef
            val text = json.encodeToString(PdfGeoreferenceCodec.encode(georef))
            val back = requireNotNull(PdfGeoreferenceCodec.decode(json.decodeFromString<PersistedGeoreference>(text))) { id }
            assertEquals(id, georef.crs, back.crs)
            assertEquals(id, georef.datum, back.datum)
            assertEquals(id, georef.affine, back.affine)
            assertEquals(id, georef.crop, back.crop)
            assertEquals(id, georef.origin, back.origin)
            assertEquals(id, georef.fit, back.fit)
        }
        // custom datum survives by value
        val custom = PdfGeoreference(
            0, GeoCrs.Geographic, GeoDatum.custom(6378206.4, 294.978698213898, -8.0, 160.0, 176.0)!!,
            PlaneAffine(1e-4, 0.0, -122.5, 0.0, 1e-4, 37.7),
            listOf(PagePoint(0.0, 0.0), PagePoint(100.0, 0.0), PagePoint(100.0, 100.0)),
            GeorefOrigin.ADOBE_VP, datumAssumed = true,
        )
        val back = requireNotNull(PdfGeoreferenceCodec.decode(PdfGeoreferenceCodec.encode(custom)))
        assertEquals(custom.datum, back.datum)
        assertTrue(back.datumAssumed)
        // junk fails closed
        val bad = PdfGeoreferenceCodec.encode(custom)
        assertNull(PdfGeoreferenceCodec.decode(bad.copy(datum = PersistedDatum("MARS2000"))))
        assertNull(PdfGeoreferenceCodec.decode(bad.copy(crs = PersistedCrs("albers"))))
        assertNull(PdfGeoreferenceCodec.decode(bad.copy(affine = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))))
        assertNull(PdfGeoreferenceCodec.decode(bad.copy(origin = "magic")))
    }

    @Test
    fun issueCodesRoundTrip() {
        val issues = listOf(PdfGeorefIssue.NoMetadata, PdfGeorefIssue.LegacyPlacement, PdfGeorefIssue.CalibrationLost) +
            GeorefRejectReason.entries.map { PdfGeorefIssue.Rejected(it) }
        for (issue in issues) {
            assertEquals(issue, PdfGeoreferenceCodec.decodeIssue(PdfGeoreferenceCodec.encodeIssue(issue)))
        }
        assertNull(PdfGeoreferenceCodec.decodeIssue(null))
    }

    @Test
    fun v1BlobsStillDecodeIntoTheV2Dto() {
        // exactly what the schema 1 store wrote
        val v1 = """{"fileName":"a.pdf","displayName":"A","pageWidth":600,"pageHeight":400,"sourceKind":"GEO_PDF",
            "calibrationKind":"parsed","calibrationCrs":"","calibration":{"fids":[],"transform":{"a":1.0,"b":0.0,"c":0.0,"d":0.0,"e":1.0,"f":0.0}},
            "coverage":{"southwest":{"latitude":-34.0,"longitude":150.0},"northeast":{"latitude":-33.0,"longitude":151.0}}}"""
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<PersistedPdfSource>(v1)
        assertEquals(1, dto.schemaVersion)
        assertNull(dto.geometry)
        assertNull(dto.georef)
        assertNotNull(dto.calibration?.transform)
        assertFalse(dto.calibration?.pageSpace == "raw")
    }
}
