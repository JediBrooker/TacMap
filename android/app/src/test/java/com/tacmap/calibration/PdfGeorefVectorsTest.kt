package com.tacmap.calibration

import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.boolOr
import com.tacmap.calibration.PdfGeorefFixture.d
import com.tacmap.calibration.PdfGeorefFixture.has
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tacmap.calibration.PdfGeorefFixture.strOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * testdata/pdf_georef.json, every section except sheets (GeoPdfSheetVectorsTest):
 * the datum table + shifts, projections both ways, /GCS parsing, rejections,
 * fiduciary fits and the tile warp. Same file the iOS suite loads.
 */
class PdfGeorefVectorsTest {
    private val root = PdfGeorefFixture.root

    @Test
    fun datumTableMatchesTheSharedTableRowForRow() {
        val table = root.obj("datums").arr("table").map { it.jsonObject }
        assertEquals(table.size, GeoDatums.table.size)
        for (row in table) {
            val id = row.str("id")
            val datum = requireNotNull(GeoDatums.byId(id)) { "no row $id" }
            assertEquals(id, row.d("a"), datum.ellipsoid.a, 0.0)
            assertEquals(id, row.d("invF"), datum.ellipsoid.invF, 0.0)
            assertEquals(id, row.str("transform"), when (datum.transform) {
                DatumTransformKind.IDENTITY -> "identity"
                DatumTransformKind.TRANSLATION3 -> "translation3"
                DatumTransformKind.HELMERT7 -> "helmert7"
            })
            assertEquals(id, row.d("dx"), datum.dx, 0.0)
            assertEquals(id, row.d("dy"), datum.dy, 0.0)
            assertEquals(id, row.d("dz"), datum.dz, 0.0)
            if (row.has("rxArcsec")) {
                assertEquals(id, row.d("rxArcsec"), datum.rxArcsec, 0.0)
                assertEquals(id, row.d("ryArcsec"), datum.ryArcsec, 0.0)
                assertEquals(id, row.d("rzArcsec"), datum.rzArcsec, 0.0)
                assertEquals(id, row.d("scalePpm"), datum.scalePpm, 0.0)
            }
            val aliases = row.obj("aliases")
            val mine = requireNotNull(GeoDatums.aliasesFor(id))
            assertEquals(id, aliases.arr("wktDatumNames").map { it.jsonPrimitive.content }, mine.wktNames)
            assertEquals(id, aliases.arr("lgiCodes").map { it.jsonPrimitive.content }, mine.lgiCodes)
            assertEquals(id, aliases.arr("lgiCodePrefixes").map { it.jsonPrimitive.content }, mine.lgiCodePrefixes)
            assertEquals(id, (aliases["epsgDatum"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.int, mine.epsgDatum)
            assertEquals(id, aliases.arr("epsgGeographic").map { it.jsonPrimitive.int }, mine.epsgGeographic)
            // every alias resolves back to this row
            mine.wktNames.forEach { assertEquals("$id wkt $it", id, GeoDatums.forWktName(it)?.id) }
            mine.lgiCodes.forEach { assertEquals("$id lgi $it", id, GeoDatums.forLgiCode(it)?.id) }
            mine.epsgGeographic.forEach { assertEquals("$id epsg $it", id, GeoDatums.forEpsgGeographic(it)?.id) }
        }
        // prefix fallbacks: NAS-X without a row is the CONUS mean, NAR-X is NAD83
        assertEquals("NAD27", GeoDatums.forLgiCode("NAS-Q")?.id)
        assertEquals("NAD83", GeoDatums.forLgiCode(" nar-c ")?.id)
        assertNull(GeoDatums.forLgiCode("ZZZ"))
    }

    @Test
    fun datumShiftsToAndFromWgs84() {
        val datums = root.obj("datums")
        val tol = datums.obj("tolerance").d("degrees")
        for (c in datums.arr("toWGS84").map { it.jsonObject }) {
            val datum = requireNotNull(GeoDatums.byId(c.str("datum")))
            val out = requireNotNull(datum.toWGS84(c.d("lat"), c.d("lon")))
            val w = PdfGeorefFixture.doubles(c["wgs84"]!!)
            assertEquals("${c.str("datum")} lat", w[0], out.latitude, tol)
            assertEquals("${c.str("datum")} lon", w[1], out.longitude, tol)
        }
        for (c in datums.arr("fromWGS84").map { it.jsonObject }) {
            val datum = requireNotNull(GeoDatums.byId(c.str("datum")))
            val w = PdfGeorefFixture.doubles(c["wgs84"]!!)
            val out = requireNotNull(datum.fromWGS84(w[0], w[1]))
            val s = PdfGeorefFixture.doubles(c["source"]!!)
            assertEquals("${c.str("datum")} lat", s[0], out.latitude, tol)
            assertEquals("${c.str("datum")} lon", s[1], out.longitude, tol)
        }
    }

    @Test
    fun projectionsForwardAndInverseMatchProj() {
        val p = root.obj("projections")
        val metres = p.obj("tolerance").d("planeMetres")
        val degrees = p.obj("tolerance").d("degrees")
        val cases = p.arr("cases").map { it.jsonObject }
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val name = c.str("name")
            val crs = PdfGeorefFixture.crsOf(c.obj("crs"))
            val ell = GeoEllipsoid(c.d("a"), c.d("invF"))
            val fwd = requireNotNull(crs.forward(c.d("lat"), c.d("lon"), ell)) { "$name forward" }
            assertEquals("$name x", c.d("x"), fwd.x, metres)
            assertEquals("$name y", c.d("y"), fwd.y, metres)
            val inv = requireNotNull(crs.inverse(c.d("x"), c.d("y"), ell)) { "$name inverse" }
            assertEquals("$name lat", c.d("inverseLat"), inv.latitude, degrees)
            assertEquals("$name lon", c.d("inverseLon"), inv.longitude, degrees)
            if (c.obj("crs").has("utmZone")) {
                val tm = crs as GeoCrs.TransverseMercator
                assertEquals(name, c.obj("crs")["utmZone"]!!.jsonPrimitive.int, tm.utmZone)
            }
        }
    }

    @Test
    fun gcsWktLiteralAndEpsgResolveToTheSharedCrsAndDatum() {
        val cases = root.obj("gcs").arr("cases").map { it.jsonObject }
        for (c in cases) {
            val id = c.str("id")
            val input = c.obj("input")
            val expected = c.obj("expected")
            val results = ArrayList<GcsResolution>()
            input.strOrNull("wkt")?.let { results += GcsParser.fromWkt(it) }
            input.strOrNull("wktPdfLiteral")?.let { literal ->
                // the raw pdf form (escaped parens, backslash-CR continuation) decodes to the same text
                val decoded = PdfGeorefFixture.decodePdfLiteral(literal)
                assertEquals("$id literal", input.str("wkt"), decoded)
                results += GcsParser.fromWkt(decoded)
            }
            input["epsg"]?.let { results += GcsParser.fromEpsg(it.jsonPrimitive.int) }
            assertTrue(id, results.isNotEmpty())
            for (r in results) {
                assertEquals("$id status", expected.str("status"), r.status.code)
                if (expected.has("crs")) PdfGeorefFixture.assertCrs(id, expected.obj("crs"), r.crs)
                if (expected.has("datum")) PdfGeorefFixture.assertDatum(id, expected.obj("datum"), r.datum)
                assertEquals("$id datumAssumed", expected.boolOr("datumAssumed", false), r.datumAssumed)
                expected.strOrNull("reason")?.let { assertEquals("$id reason", it, r.reason) }
            }
        }
    }

    @Test
    fun unsupportedProjectionFallsBackToLocalTransverseMercator() {
        val ex = root.obj("gcs").obj("fallbackExample")
        val input = ex.obj("input")
        val vp = GeoPdfViewportData(
            name = "fallback",
            bbox = PdfGeorefFixture.nums(input["bbox"]),
            lpts = PdfGeorefFixture.nums(input["lpts"]),
            gpts = PdfGeorefFixture.nums(input["gpts"]),
            gcsWkt = input.obj("gcs").str("wkt"),
        )
        val result = GeoPdfGeoreferencer.fromViewports(listOf(vp))
        assertTrue(result is GeoPdfGeorefResult.Georeferenced)
        val g = (result as GeoPdfGeorefResult.Georeferenced).georef
        val expected = ex.obj("expected")
        PdfGeorefFixture.assertCrs("fallback", expected.obj("crs"), g.crs)
        PdfGeorefFixture.assertDatum("fallback", expected.obj("datum"), g.datum)
        val affine = PdfGeorefFixture.doubles(expected["affine"]!!)
        val tol = PdfGeorefFixture.doubles(expected["affineTol"]!!)
        g.affine.coefficients.forEachIndexed { i, v -> assertEquals("affine[$i]", affine[i], v, tol[i]) }
        val fit = expected.obj("fit")
        assertEquals(fit.d("rmsMetres"), requireNotNull(g.fit).rmsMetres, 1e-3)
        PdfGeorefFixture.assertFitStats("fallback", fit, result.fitStats)
        PdfGeorefFixture.assertChecks("fallback", g, expected.arr("checks"), 1e-3, 1e-3, 1e-2)
    }

    @Test
    fun rejectionsGiveTheSharedReasonCodeAndContrastCasesAccept() {
        val cases = root.obj("rejections").arr("cases").map { it.jsonObject }
        // 47 from the first round + the lgiRules.valueTypes cases (zone / hemisphere / type)
        assertTrue("only ${cases.size} cases", cases.size >= 54)
        val reasons = root.obj("rejections").obj("reasons").keys
        GeorefRejectReason.entries.forEach { assertTrue(it.code, it.code in reasons) }
        for (c in cases) {
            val id = c.str("id")
            val page = PdfGeorefFixture.pageData(c, c.obj("input"))
            val result = GeoPdfGeoreferencer.build(page)
            val expected = c.obj("expected")
            if (expected.strOrNull("outcome") == "reject") {
                assertTrue("$id should reject, got $result", result is GeoPdfGeorefResult.Rejected)
                val rejected = result as GeoPdfGeorefResult.Rejected
                assertEquals(id, expected.str("reason"), rejected.reason.code)
                // rmsGate carries the fit it failed on (passesGate false, the real diagonal + limit)
                if (expected.has("fit")) PdfGeorefFixture.assertFitStats(id, expected.obj("fit"), rejected.fitStats)
                else assertNull("$id fitStats", rejected.fitStats)
            } else {
                // a few structured inputs leave the viewport /Name off (the pdf form has it)
                val named = c.obj("input")["viewports"]?.jsonArray?.all { it.jsonObject.containsKey("name") } ?: true
                PdfGeorefFixture.assertGeoref(id, expected, result, checkName = named)
            }
        }
    }

    private fun assertParsed(label: String, want: JsonObject, ref: FiduciaryReference?) {
        assertNotNull("$label should parse", ref)
        when (want.str("kind")) {
            "latlon" -> {
                val r = ref as? FiduciaryReference.LatLon ?: error("$label: wanted latlon, got $ref")
                assertEquals(label, want.d("lat"), r.latitude, 0.0)
                assertEquals(label, want.d("lon"), r.longitude, 0.0)
            }
            "mgrs", "utm" -> {
                val (zone, band, southern, e, n) = when (ref) {
                    is FiduciaryReference.Mgrs -> {
                        assertEquals(label, "mgrs", want.str("kind"))
                        GridRef(ref.zone, ref.band, ref.southern, ref.easting, ref.northing)
                    }
                    is FiduciaryReference.Utm -> {
                        assertEquals(label, "utm", want.str("kind"))
                        GridRef(ref.zone, ref.band, ref.southern, ref.easting, ref.northing)
                    }
                    else -> error("$label: wanted ${want.str("kind")}, got $ref")
                }
                assertEquals(label, want["zone"]!!.jsonPrimitive.int, zone)
                assertEquals(label, want.str("band")[0], band)
                assertEquals(label, want.str("hemisphere") == "S", southern)
                assertEquals(label, want.d("easting"), e, 0.0)
                assertEquals(label, want.d("northing"), n, 0.0)
            }
            else -> error("$label: unknown parsed kind ${want.str("kind")}")
        }
    }

    private data class GridRef(val zone: Int, val band: Char, val southern: Boolean, val e: Double, val n: Double)

    @Test
    fun fiduciaryReferencesParseToTheSharedForms() {
        for (set in root.obj("fiduciaryFits").arr("sets").map { it.jsonObject }) {
            val datum = requireNotNull(GeoDatums.byId(set.str("datum")))
            for (p in set.arr("points").map { it.jsonObject }) {
                val input = p.str("input")
                assertParsed("${set.str("name")} '$input'", p.obj("parsed"), FiduciaryReferenceParser.parse(input, datum))
            }
        }
    }

    /** fiduciaryFits.parseCases: the grammar both apps share, refusals (parsed null) included */
    @Test
    fun fiduciaryReferenceParseCases() {
        val cases = root.obj("fiduciaryFits").arr("parseCases").map { it.jsonObject }
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val input = c.str("input")
            val got = FiduciaryReferenceParser.parse(input)
            val want = c["parsed"]
            if (want == null || want is JsonNull) {
                assertNull("'$input' must be refused: ${c.strOrNull("note")}", got)
            } else {
                assertParsed("'$input'", want.jsonObject, got)
            }
        }
    }

    /** fiduciaryFits.whiteSpaceCodePoints is the whole list, every other BMP char is left alone */
    @Test
    fun referenceWhitespaceIsExactlyTheSharedSet() {
        val shared = root.obj("fiduciaryFits").arr("whiteSpaceCodePoints").map { it.jsonPrimitive.int }.toSet()
        assertTrue(shared.size >= 29)
        for (code in 0..0xFFFF) {
            assertEquals("U+%04X".format(code), code in shared, FiduciaryReferenceParser.isReferenceSpace(code.toChar()))
        }
    }

    /**
     * fiduciaryFits.storedSets through the path production uses (MapScreen apply,
     * MapScreenHelpers library restore, the v1 migration): saved Fiduciary ->
     * refitStored -> georeference. Zone has to come off the first point's typed
     * MGRS/UTM, not its longitude, or the printed grid is a metre+ off.
     */
    @Test
    fun storedFiduciariesRefitThroughTheProductionPath() {
        val section = root.obj("fiduciaryFits")
        val tol = section.obj("tolerance")
        val sets = section.arr("storedSets").map { it.jsonObject }
        assertEquals(3, sets.size)
        for (set in sets) {
            val name = set.str("name")
            val fids = set.arr("fiduciaries").map { it.jsonObject }.map {
                Fiduciary(pdfX = it.d("pdfX"), pdfY = it.d("pdfY"), mgrs = it.str("mgrs"), latitude = it.d("latitude"), longitude = it.d("longitude"))
            }
            val c = PdfGeorefFixture.doubles(set["cropBBox"]!!)
            val crop = listOf(PagePoint(c[0], c[1]), PagePoint(c[2], c[1]), PagePoint(c[2], c[3]), PagePoint(c[0], c[3]))
            val e = set.obj("expected")
            val wantZone = e["zone"]!!.jsonPrimitive.int
            val wantSouth = e.str("hemisphere") == "S"
            assertEquals(name, wantZone to wantSouth, FiduciaryFitter.storedZone(fids[0]))
            assertEquals("$name standard zone", e["standardZoneOfFirstPoint"]!!.jsonPrimitive.int, GeoCrs.standardUtmZone(fids[0].longitude))

            val r = requireNotNull(FiduciaryFitter.refitStored(fids, crop)) { name }
            assertEquals(name, wantZone, r.zone)
            assertEquals(name, wantSouth, r.southern)
            assertEquals("$name crossValidated", e["crossValidated"]!!.jsonPrimitive.content.toBoolean(), r.crossValidated)
            val plane = e.arr("planePoints").map { PdfGeorefFixture.doubles(it) }
            assertEquals(name, plane.size, r.planePoints.size)
            plane.zip(r.planePoints).forEachIndexed { i, (w, a) ->
                assertEquals("$name plane[$i].x", w[0], a.x, tol.d("planeMetres"))
                assertEquals("$name plane[$i].y", w[1], a.y, tol.d("planeMetres"))
            }

            val g = requireNotNull(r.georeference(crop)) { "$name georef" }
            assertEquals(GeorefOrigin.FIDUCIARIES, g.origin)
            PdfGeorefFixture.assertCrs(name, e.obj("crs"), g.crs)
            PdfGeorefFixture.assertDatum(name, e.obj("datum"), g.datum)
            // fixture affine is 15 sig figs, same relative bar iOS uses
            PdfGeorefFixture.doubles(e["affine"]!!).zip(g.affine.coefficients).forEachIndexed { i, (w, a) ->
                assertEquals("$name affine[$i]", w, a, 1e-7 * maxOf(1.0, abs(w)))
            }
            val fit = requireNotNull(g.fit) { "$name fit" }
            val residualTol = tol.d("residualMetres")
            val residuals = PdfGeorefFixture.doubles(e["residualsMetres"]!!)
            assertEquals(name, residuals.size, fit.perPointMetres.size)
            residuals.zip(fit.perPointMetres).forEachIndexed { i, (w, a) -> assertEquals("$name residual[$i]", w, a, residualTol) }
            assertEquals("$name rms", e.d("rmsMetres"), fit.rmsMetres, residualTol)
            assertEquals("$name max", e.d("maxResidualMetres"), fit.maxResidualMetres, residualTol)
            assertEquals("$name fit.crossValidated", e["crossValidated"]!!.jsonPrimitive.content.toBoolean(), fit.crossValidated)
            PdfGeorefFixture.assertChecks(name, g, e.arr("checks"), tol.d("pagePoints"), tol.d("planeMetres"), tol.d("wgs84Metres"))

            // and the printed grid lands. in the standard zone it doesn't (1.4 m / 0.8 m)
            val limit = e.d("truthToleranceMetres")
            var worst = 0.0
            for (t in e.arr("truth").map { it.jsonObject }) {
                val p = PdfGeorefFixture.point(t["page"]!!)
                val w = PdfGeorefFixture.doubles(t["wgs84"]!!)
                val got = requireNotNull(g.toWGS84(p.x, p.y)) { "$name ${t.str("label")}" }
                val err = PdfGeorefFixture.metres(w[0], w[1], got.latitude, got.longitude)
                assertTrue("$name ${t.str("label")} off by $err m", err <= limit)
                worst = maxOf(worst, err)
            }
            assertEquals("$name model error", e.d("modelErrorMetres"), worst, 0.01)
            if (e.has("standardZoneModelErrorMetres")) {
                // the zone choice is what's being pinned: the standard zone really is worse
                assertTrue("$name standard zone should miss the grid", e.d("standardZoneModelErrorMetres") > limit)
            }
        }
    }

    @Test
    fun fiduciaryFitsMatchTheSharedFitCore() {
        val section = root.obj("fiduciaryFits")
        val tol = section.obj("tolerance")
        for (set in section.arr("sets").map { it.jsonObject }) {
            val name = set.str("name")
            val datum = requireNotNull(GeoDatums.byId(set.str("datum")))
            val crop = PdfGeorefFixture.doubles(set["cropBBox"]!!).toDoubleArray()
            val points = set.arr("points").map { it.jsonObject }.map {
                FiduciaryPoint(
                    PdfGeorefFixture.point(it["page"]!!),
                    requireNotNull(FiduciaryReferenceParser.parse(it.str("input"), datum)),
                )
            }
            val r = requireNotNull(FiduciaryFitter.fit(points, datum, crop)) { name }
            val e = set.obj("expected")
            assertEquals(name, e["zone"]!!.jsonPrimitive.int, r.zone)
            assertEquals(name, e.str("hemisphere") == "S", r.southern)
            PdfGeorefFixture.assertCrs(name, e.obj("crs"), r.crs)
            PdfGeorefFixture.assertDatum(name, e.obj("datum"), r.datum)
            e.arr("planePoints").map { PdfGeorefFixture.doubles(it) }.zip(r.planePoints).forEachIndexed { i, (x, a) ->
                assertEquals("$name plane[$i].x", x[0], a.x, tol.d("planeMetres"))
                assertEquals("$name plane[$i].y", x[1], a.y, tol.d("planeMetres"))
            }
            // fixture rounds to 6 significant figures, so allow half a unit of that on top
            val wantRatio = e.d("eigenRatio")
            val sigUnit = if (wantRatio > 0.0) 10.0.pow(floor(log10(wantRatio)) - 5.0) else 1e-12
            assertEquals("$name eigenRatio", wantRatio, r.eigenRatio, tol.d("eigenRatioRelative") * wantRatio + 0.5 * sigUnit)
            assertEquals("$name degenerate", e["degenerate"]!!.jsonPrimitive.content.toBoolean(), r.degenerate)
            val span = PdfGeorefFixture.doubles(e["spanFraction"]!!)
            assertEquals("$name spanX", span[0], r.spanFraction.first, 1e-6)
            assertEquals("$name spanY", span[1], r.spanFraction.second, 1e-6)
            assertEquals("$name spanWarning", e["spanWarning"]!!.jsonPrimitive.content.toBoolean(), r.spanWarning)
            assertEquals("$name crossValidated", e["crossValidated"]!!.jsonPrimitive.content.toBoolean(), r.crossValidated)
            if (e["affine"] is JsonNull) {
                assertNull("$name affine", r.affine)
                assertNull("$name georef", r.georeference(listOf(PagePoint(0.0, 0.0), PagePoint(1.0, 0.0), PagePoint(1.0, 1.0))))
                continue
            }
            val affine = requireNotNull(r.affine) { "$name affine" }
            // compare the affine where it matters, at the crop corners, in plane metres
            val want = PlaneAffine.of(PdfGeorefFixture.doubles(e["affine"]!!))!!
            for ((x, y) in listOf(crop[0] to crop[1], crop[2] to crop[1], crop[2] to crop[3], crop[0] to crop[3])) {
                val a = affine.apply(x, y)
                val w = want.apply(x, y)
                assertEquals("$name affine at $x,$y", 0.0, kotlin.math.hypot(a.x - w.x, a.y - w.y), tol.d("planeMetres"))
            }
            PdfGeorefFixture.doubles(e["residualsMetres"]!!).zip(r.residualsMetres).forEachIndexed { i, (x, a) ->
                assertEquals("$name residual[$i]", x, a, tol.d("residualMetres"))
            }
            assertEquals("$name rms", e.d("rmsMetres"), r.rmsMetres, tol.d("residualMetres"))
            assertEquals("$name max", e.d("maxResidualMetres"), r.maxResidualMetres, tol.d("residualMetres"))
            if (e.has("leaveOneOutMetres")) {
                val loo = e.arr("leaveOneOutMetres").map { PdfGeorefFixture.num(it) }
                val looRms = e.arr("leaveOneOutRmsMetres").map { PdfGeorefFixture.num(it) }
                assertEquals(name, loo.size, r.leaveOneOutMetres!!.size)
                loo.zip(r.leaveOneOutMetres!!).forEachIndexed { i, (x, a) ->
                    if (x == null) assertNull("$name loo[$i]", a) else assertEquals("$name loo[$i]", x, a!!, tol.d("residualMetres"))
                }
                looRms.zip(r.leaveOneOutRmsMetres!!).forEachIndexed { i, (x, a) ->
                    if (x == null) assertNull("$name looRms[$i]", a) else assertEquals("$name looRms[$i]", x, a!!, tol.d("residualMetres"))
                }
                assertEquals("$name outliers", e.arr("flaggedOutliers").map { it.jsonPrimitive.int }, r.flaggedOutliers)
            }
            assertEquals("$name exactFit", e.boolOr("exactFit", false), r.exactFit)
            e.strOrNull("message")?.let { assertEquals(name, it, r.message) }
            val cropPoly = listOf(PagePoint(crop[0], crop[1]), PagePoint(crop[2], crop[1]), PagePoint(crop[2], crop[3]), PagePoint(crop[0], crop[3]))
            val g = requireNotNull(r.georeference(cropPoly)) { "$name georef" }
            assertEquals(GeorefOrigin.FIDUCIARIES, g.origin)
            PdfGeorefFixture.assertChecks(name, g, e.arr("checks"), tol.d("pagePoints"), tol.d("planeMetres"), tol.d("wgs84Metres"))
        }
    }

    @Test
    fun tileWarpPixelToPageMatchesTheShared256pxWarp() {
        val section = root.obj("tileWarp")
        val pageTol = section.obj("tolerance").d("pagePoints")
        val degTol = section.obj("tolerance").d("degrees")
        val sheets = root.arr("sheets").map { it.jsonObject }.associateBy { it.str("id") }
        val tiles = section.arr("tiles").map { it.jsonObject }
        assertEquals(16, tiles.size)
        for (t in tiles) {
            val sheet = requireNotNull(sheets[t.str("georef")])
            val e = sheet.obj("expected")
            val georef = PdfGeoreference(
                page = 0,
                crs = PdfGeorefFixture.crsOf(e.obj("crs")),
                datum = PdfGeorefFixture.datumOf(e.obj("datum")),
                affine = PlaneAffine.of(PdfGeorefFixture.doubles(e["affine"]!!))!!,
                crop = e.arr("crop").map(PdfGeorefFixture::point),
                origin = GeorefOrigin.ADOBE_VP,
            )
            val z = t["z"]!!.jsonPrimitive.int
            val x = t["x"]!!.jsonPrimitive.int
            val y = t["y"]!!.jsonPrimitive.int
            for (s in t.arr("samples").map { it.jsonObject }) {
                val px = PdfGeorefFixture.doubles(s["px"]!!)
                val w = requireNotNull(TileWarp.pixelToWgs84(z, x, y, px[0], px[1]))
                val ew = PdfGeorefFixture.doubles(s["wgs84"]!!)
                assertEquals("${t.str("georef")} z$z lat", ew[0], w.latitude, degTol)
                assertEquals("${t.str("georef")} z$z lon", ew[1], w.longitude, degTol)
                val page = requireNotNull(TileWarp.pixelToPage(georef, z, x, y, px[0], px[1]))
                val ep = PdfGeorefFixture.point(s["page"]!!)
                assertEquals("${t.str("georef")} z$z px $px page.x", ep.x, page.x, pageTol)
                assertEquals("${t.str("georef")} z$z px $px page.y", ep.y, page.y, pageTol)
            }
        }
    }

    @Test
    fun projectionRoundTripsStayInsideTheZone() {
        // belt and braces on top of PROJ: forward then inverse lands back within 1e-9 deg
        val utm = GeoCrs.utm(33, false)
        for (lat in listOf(0.5, 30.0, 60.0, 70.0, 80.0)) for (dLon in listOf(-3.9, -1.0, 0.0, 2.5, 3.9)) {
            val p = requireNotNull(utm.forward(lat, 15.0 + dLon, GeoEllipsoid.WGS84))
            val back = requireNotNull(utm.inverse(p.x, p.y, GeoEllipsoid.WGS84))
            assertEquals(lat, back.latitude, 1e-9)
            assertEquals(15.0 + dLon, back.longitude, 1e-9)
        }
        // silly input fails closed instead of making up a place
        assertNull(utm.forward(Double.NaN, 15.0, GeoEllipsoid.WGS84))
        assertNull(utm.inverse(1e12, 0.0, GeoEllipsoid.WGS84))
        assertFalse(GeoEllipsoid(1.0, 0.0).isValid())
        assertTrue(abs(GeoCrs.mercatorScaleAt(0.0, GeoEllipsoid.WGS84) - 1.0) < 1e-15)
    }
}
