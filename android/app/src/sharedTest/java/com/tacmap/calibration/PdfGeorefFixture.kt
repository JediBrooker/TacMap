package com.tacmap.calibration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * testdata/pdf_georef.json loader + the json -> model converters every georef
 * vector test shares, JVM unit tests and on-device ones alike (src/sharedTest).
 * On the JVM it walks up from user.dir the same way SharedVectorsTest does; the
 * instrumented tests point [readBytes] at the test apk's assets instead.
 */
internal object PdfGeorefFixture {
    /** testdata/<name> as bytes */
    @Volatile
    var readBytes: (String) -> ByteArray = { name -> file(name).readBytes() }

    val root: JsonObject by lazy { Json.parseToJsonElement(readBytes("pdf_georef.json").decodeToString()).jsonObject }

    /** JVM only */
    fun file(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/$name")
            if (f.exists()) return f
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name from ${System.getProperty("user.dir")}")
    }

    fun JsonObject.obj(k: String): JsonObject = this[k]!!.jsonObject
    fun JsonObject.arr(k: String): JsonArray = this[k]!!.jsonArray
    fun JsonObject.d(k: String): Double = this[k]!!.jsonPrimitive.double
    fun JsonObject.str(k: String): String = this[k]!!.jsonPrimitive.content
    fun JsonObject.strOrNull(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.boolOr(k: String, default: Boolean): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: default
    fun JsonObject.has(k: String): Boolean = this[k] != null && this[k] !is JsonNull

    /** json numbers, with "Infinity"/"NaN" strings meaning what Double() makes of them */
    fun num(e: JsonElement): Double? {
        if (e is JsonNull) return null
        val p = e.jsonPrimitive
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }

    /** absent -> null; something that isn't an array stands for a wrong-typed pdf value -> [null] */
    fun nums(e: JsonElement?): List<Double?>? = when (e) {
        null, is JsonNull -> null
        is JsonArray -> e.map(::num)
        else -> listOf(null)
    }
    fun doubles(e: JsonElement): List<Double> = e.jsonArray.map { it.jsonPrimitive.double }
    fun point(e: JsonElement): PagePoint = doubles(e).let { PagePoint(it[0], it[1]) }

    fun ellipsoidOf(datumId: String): GeoEllipsoid = requireNotNull(GeoDatums.byId(datumId)).ellipsoid

    fun crsOf(o: JsonObject): GeoCrs = when (o.str("kind")) {
        "geographic" -> GeoCrs.Geographic
        "transverseMercator" -> GeoCrs.TransverseMercator(o.d("lat0"), o.d("lon0"), o.d("k0"), o.d("fe"), o.d("fn"))
        "lambertConformalConic2SP" -> GeoCrs.LambertConformalConic2SP(o.d("lat1"), o.d("lat2"), o.d("lat0"), o.d("lon0"), o.d("fe"), o.d("fn"))
        "lambertConformalConic1SP" -> GeoCrs.LambertConformalConic1SP(o.d("lat0"), o.d("lon0"), o.d("k0"), o.d("fe"), o.d("fn"))
        "mercator1SP" -> GeoCrs.Mercator1SP(o.d("lon0"), o.d("k0"), o.d("fe"), o.d("fn"))
        else -> error("unknown crs kind ${o.str("kind")}")
    }

    fun datumOf(o: JsonObject): GeoDatum = when (val id = o.str("id")) {
        "custom" -> requireNotNull(GeoDatum.custom(o.d("a"), o.d("invF"), o.d("dx"), o.d("dy"), o.d("dz")))
        else -> requireNotNull(GeoDatums.byId(id)) { "no datum $id" }
    }

    fun assertCrs(label: String, expected: JsonObject, actual: GeoCrs?) {
        assertNotNull("$label crs", actual)
        val kind = expected.str("kind")
        fun close(k: String, v: Double) = assertEquals("$label crs.$k", expected.d(k), v, 1e-9 * maxOf(1.0, abs(expected.d(k))))
        when (actual) {
            GeoCrs.Geographic -> assertEquals(label, "geographic", kind)
            is GeoCrs.TransverseMercator -> {
                assertEquals(label, "transverseMercator", kind)
                close("lat0", actual.lat0); close("lon0", actual.lon0); close("k0", actual.k0)
                close("fe", actual.fe); close("fn", actual.fn)
                if (expected.has("utmZone")) {
                    assertEquals("$label utmZone", expected["utmZone"]!!.jsonPrimitive.int, actual.utmZone)
                    assertEquals("$label hemisphere", expected.str("hemisphere")[0], actual.hemisphere)
                }
            }
            is GeoCrs.LambertConformalConic2SP -> {
                assertEquals(label, "lambertConformalConic2SP", kind)
                close("lat1", actual.lat1); close("lat2", actual.lat2); close("lat0", actual.lat0)
                close("lon0", actual.lon0); close("fe", actual.fe); close("fn", actual.fn)
            }
            is GeoCrs.LambertConformalConic1SP -> {
                assertEquals(label, "lambertConformalConic1SP", kind)
                close("lat0", actual.lat0); close("lon0", actual.lon0); close("k0", actual.k0)
                close("fe", actual.fe); close("fn", actual.fn)
            }
            is GeoCrs.Mercator1SP -> {
                assertEquals(label, "mercator1SP", kind)
                close("lon0", actual.lon0); close("k0", actual.k0); close("fe", actual.fe); close("fn", actual.fn)
            }
            null -> Unit
        }
    }

    fun assertDatum(label: String, expected: JsonObject, actual: GeoDatum?) {
        assertNotNull("$label datum", actual)
        val id = expected.str("id")
        assertEquals("$label datum id", id, actual!!.id)
        if (id == "custom") {
            assertEquals("$label datum a", expected.d("a"), actual.ellipsoid.a, 1e-9)
            assertEquals("$label datum invF", expected.d("invF"), actual.ellipsoid.invF, 1e-9)
            assertEquals("$label datum dx", expected.d("dx"), actual.dx, 1e-9)
            assertEquals("$label datum dy", expected.d("dy"), actual.dy, 1e-9)
            assertEquals("$label datum dz", expected.d("dz"), actual.dz, 1e-9)
        }
    }

    /** metres between two WGS84 points, local flat earth, fine at cm scale */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = PI / 180.0 * 6_378_137.0
        return hypot((lat2 - lat1) * k, (lon2 - lon1) * k * cos(lat1 * PI / 180.0))
    }

    /** plane distance in metres, degrees get local radii for a geographic plane */
    fun planeMetres(georef: PdfGeoreference, a: PlanePoint, b: List<Double>): Double {
        if (georef.crs !is GeoCrs.Geographic) return hypot(a.x - b[0], a.y - b[1])
        val (me, mn) = metresPerPlaneUnit(georef.crs, georef.datum.ellipsoid, b[1])
        return hypot((a.x - b[0]) * me, (a.y - b[1]) * mn)
    }

    // ---- fixture 'written' / 'input' -> what the pdf reader would have produced

    fun viewportData(o: JsonObject): GeoPdfViewportData {
        // rejections.note: a gcs that isn't an object, a non-string wkt or a non-integer
        // epsg stand in for the wrong pdf types
        val rawGcs = o["gcs"]?.takeIf { it !is JsonNull }
        val gcs = rawGcs as? JsonObject
        var malformed = rawGcs != null && gcs == null
        val wkt = gcs?.get("wkt")?.let { w ->
            (w as? JsonPrimitive)?.takeIf { it.isString }?.content ?: run { malformed = true; null }
        }
        val epsg = gcs?.get("epsg")?.let { e ->
            (e as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull() ?: run { malformed = true; null }
        }
        return GeoPdfViewportData(
            name = o.strOrNull("name"),
            isGeo = true,
            bbox = nums(o["bbox"]),
            gpts = nums(o["gpts"]),
            lpts = nums(o["lpts"]),
            bounds = nums(o["bounds"]),
            gcsWkt = wkt,
            gcsEpsg = epsg,
            gcsMalformed = malformed,
        )
    }

    // lgiRules.valueTypes stand-ins: a json string is a pdf string (numeric ones read as
    // numbers, junk like "ten" stands for a name), a number is a pdf number, anything
    // else is some other pdf type. Present-but-wrong never falls back to /Display
    private fun lgiText(e: JsonElement?): String? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive && e.isString -> e.content
        else -> GeoPdfParser.INVALID_TEXT
    }

    private fun lgiNumber(e: JsonElement?): Double? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive -> num(e) ?: Double.NaN
        else -> Double.NaN
    }

    private fun lgiProjection(p: JsonObject): GeoPdfLgiProjectionData {
        val params = HashMap<String, Double?>()
        for (key in listOf("CentralMeridian", "OriginLatitude", "FalseEasting", "FalseNorthing", "ScaleFactor",
            "StandardParallelOne", "StandardParallelTwo")) {
            p[key]?.let { params[key] = num(it) }
        }
        val datum = when (val d = p["Datum"]) {
            null, is JsonNull -> null
            is JsonObject -> {
                val ell = d["Ellipsoid"] as? JsonObject
                LgiDatumData.Inline(
                    semiMajorAxis = ell?.get("SemiMajorAxis")?.let(::num),
                    inverseFlattening = ell?.get("InvFlattening")?.let(::num),
                    toWgs84 = when (val t = d["ToWGS84"]) {
                        null, is JsonNull -> null
                        is JsonObject -> listOf("dx", "dy", "dz").map { k -> t[k]?.let(::num) }
                        else -> listOf(null)
                    },
                )
            }
            is JsonPrimitive -> LgiDatumData.Code(d.content)
            else -> LgiDatumData.Invalid
        }
        return GeoPdfLgiProjectionData(
            // anything but text (the fixture uses an object for a pdf integer) is malformed
            projectionType = (p["ProjectionType"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            zone = lgiNumber(p["Zone"]),
            hemisphere = lgiText(p["Hemisphere"]),
            datum = datum,
            parameters = params,
            units = lgiText(p["Units"]),
        )
    }

    fun lgiEntryData(o: JsonObject): GeoPdfLgiEntryData = GeoPdfLgiEntryData(
        description = o.strOrNull("description"),
        ctm = nums(o["ctm"]),
        registration = when (val r = o["registration"]) {
            null, is JsonNull -> null
            is JsonArray -> r.map { nums(it) }
            else -> listOf(null)
        },
        neatline = nums(o["neatline"]),
        projection = (o["projection"] as? JsonObject)?.let(::lgiProjection),
        display = (o["display"] as? JsonObject)?.let(::lgiProjection),
    )

    fun pageData(sheetOrCase: JsonObject, written: JsonObject?): GeoPdfPageData {
        val media = sheetOrCase["mediaBox"]?.takeIf { it !is JsonNull }?.let(::doubles)
        val crop = sheetOrCase["cropBox"]?.takeIf { it !is JsonNull }?.let(::doubles)
        val rotate = (sheetOrCase["rotate"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val viewports = written?.get("viewports")?.jsonArray?.map { viewportData(it.jsonObject) } ?: emptyList()
        // entries that isn't an array = an /LGIDict that's neither array nor dict: declared, nothing in it
        val rawEntries = written?.get("entries")?.takeIf { it !is JsonNull }
        val entries = (rawEntries as? JsonArray)?.map { lgiEntryData(it.jsonObject) }
        return GeoPdfPageData(
            mediaBox = media,
            cropBox = crop,
            rotate = rotate,
            viewports = viewports,
            lgiDeclared = rawEntries != null,
            lgiEntries = entries ?: emptyList(),
        )
    }

    /**
     * Everything a sheet / accepted rejection case pins: origin, selection, crs,
     * datum, crop, affine, controls, fit, then every check row both ways.
     */
    fun assertGeoref(label: String, expected: JsonObject, result: GeoPdfGeorefResult, checkName: Boolean = true) {
        assertTrue("$label: expected a georef, got $result", result is GeoPdfGeorefResult.Georeferenced)
        val ok = result as GeoPdfGeorefResult.Georeferenced
        val g = ok.georef
        assertEquals("$label origin", expected.str("origin"), g.origin.code)
        val sel = expected.obj("selected")
        assertEquals("$label selected.kind", sel.str("kind"), ok.selection.kind)
        assertEquals("$label selected.index", sel["index"]!!.jsonPrimitive.int, ok.selection.index)
        if (checkName) sel.strOrNull("name")?.let { assertEquals("$label selected.name", it, ok.selection.name) }
        sel.strOrNull("description")?.let { assertEquals("$label selected.description", it, ok.selection.name) }
        sel.strOrNull("source")?.let { assertEquals("$label selected.source", it, ok.selection.source) }
        assertCrs(label, expected.obj("crs"), g.crs)
        assertDatum(label, expected.obj("datum"), g.datum)
        (expected["datumAssumed"] as? JsonPrimitive)?.booleanOrNull?.let { assertEquals("$label datumAssumed", it, g.datumAssumed) }

        val tol = expected["tolerances"]?.jsonObject
        val pageTol = tol?.let { it.d("pagePoints") } ?: 1e-3
        val planeTol = tol?.let { it.d("planeMetres") } ?: 1e-3
        val wgsTol = tol?.let { it.d("wgs84Metres") } ?: 1e-2
        val rmsTol = tol?.let { it.d("rmsMetres") } ?: 1e-3

        val crop = expected.arr("crop").map(::point)
        assertEquals("$label crop size", crop.size, g.crop.size)
        crop.zip(g.crop).forEachIndexed { i, (e, a) ->
            assertEquals("$label crop[$i].x", e.x, a.x, 1e-6)
            assertEquals("$label crop[$i].y", e.y, a.y, 1e-6)
        }
        val affine = doubles(expected["affine"]!!)
        val affineTol = expected["affineTol"]?.let(::doubles) ?: List(6) { 1e-6 }
        g.affine.coefficients.forEachIndexed { i, v ->
            assertEquals("$label affine[$i]", affine[i], v, affineTol[i])
        }
        expected["controls"]?.jsonArray?.let { controls ->
            assertEquals("$label control count", controls.size, ok.controls.size)
            controls.map { it.jsonObject }.zip(ok.controls).forEachIndexed { i, (e, a) ->
                val p = point(e["page"]!!)
                assertEquals("$label control[$i].page.x", p.x, a.page.x, pageTol)
                assertEquals("$label control[$i].page.y", p.y, a.page.y, pageTol)
                val gp = doubles(e["gpts"]!!)
                assertEquals("$label control[$i].lat", gp[0], a.geodetic.latitude, 1e-12)
                assertEquals("$label control[$i].lon", gp[1], a.geodetic.longitude, 1e-12)
                assertTrue("$label control[$i].plane", planeMetres(g, a.plane, doubles(e["plane"]!!)) <= planeTol)
            }
        }
        val fit = expected["fit"]?.takeIf { it !is JsonNull }?.jsonObject
        if (fit != null) {
            val actual = requireNotNull(g.fit) { "$label fit missing" }
            assertEquals("$label rms", fit.d("rmsMetres"), actual.rmsMetres, rmsTol)
            assertEquals("$label maxResidual", fit.d("maxResidualMetres"), actual.maxResidualMetres, rmsTol)
            assertEquals("$label residual count", fit.arr("residualsMetres").size, actual.perPointMetres.size)
            doubles(fit["residualsMetres"]!!).zip(actual.perPointMetres).forEachIndexed { i, (e, a) ->
                assertEquals("$label residual[$i]", e, a, rmsTol)
            }
            assertEquals("$label crossValidated", actual.perPointMetres.size >= 4, actual.crossValidated)
            assertFitStats(label, fit, ok.fitStats, rmsTol)
        } else {
            // nothing fitted (a CTM won), so no stats and no fit on the georef
            assertNull("$label fitStats", ok.fitStats)
            assertNull("$label fit", g.fit)
        }
        assertChecks(label, g, expected.arr("checks"), pageTol, planeTol, wgsTol)
    }

    /** expected.fit against what the gate actually looked at, passesGate included */
    fun assertFitStats(label: String, fit: JsonObject, actual: GeorefFitStats?, tol: Double = 1e-3) {
        assertNotNull("$label fitStats", actual)
        actual!!
        assertEquals("$label stats rms", fit.d("rmsMetres"), actual.rmsMetres, tol)
        assertEquals("$label stats max", fit.d("maxResidualMetres"), actual.maxResidualMetres, tol)
        val residuals = doubles(fit["residualsMetres"]!!)
        assertEquals("$label stats residual count", residuals.size, actual.residualsMetres.size)
        residuals.zip(actual.residualsMetres).forEachIndexed { i, (e, a) -> assertEquals("$label stats residual[$i]", e, a, tol) }
        assertEquals("$label sheetDiagonalMetres", fit.d("sheetDiagonalMetres"), actual.sheetDiagonalMetres, tol)
        assertEquals("$label gateLimitMetres", fit.d("gateLimitMetres"), actual.gateLimitMetres, tol)
        assertEquals("$label passesGate", fit["passesGate"]!!.jsonPrimitive.booleanOrNull, actual.passesGate)
    }

    fun assertChecks(
        label: String,
        g: PdfGeoreference,
        checks: JsonArray,
        pageTol: Double,
        planeTol: Double,
        wgsTol: Double,
    ) {
        assertTrue("$label has checks", checks.isNotEmpty())
        for (cEl in checks) {
            val c = cEl.jsonObject
            val what = "$label check '${c.str("label")}'"
            val page = point(c["page"]!!)
            val plane = g.planeOf(page.x, page.y)
            assertTrue("$what plane", planeMetres(g, plane, doubles(c["plane"]!!)) <= planeTol)
            val w = doubles(c["wgs84"]!!)
            val actual = requireNotNull(g.toWGS84(page.x, page.y)) { "$what toWGS84 null" }
            val err = metres(w[0], w[1], actual.latitude, actual.longitude)
            assertTrue("$what toWGS84 off by $err m", err <= wgsTol)
            val back = requireNotNull(g.toPage(w[0], w[1])) { "$what toPage null" }
            val tp = point(c["toPage"]!!)
            assertEquals("$what toPage.x", tp.x, back.x, pageTol)
            assertEquals("$what toPage.y", tp.y, back.y, pageTol)
        }
    }

    /** decode the body of a pdf literal string (no outer parens), PDF 32000 7.3.4.2 */
    fun decodePdfLiteral(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') { out.append(c); i++; continue }
            i++
            if (i >= raw.length) break
            when (val n = raw[i]) {
                'n' -> { out.append('\n'); i++ }
                'r' -> { out.append('\r'); i++ }
                't' -> { out.append('\t'); i++ }
                'b' -> { out.append('\b'); i++ }
                'f' -> { out.append('\u000c'); i++ }
                '(', ')', '\\' -> { out.append(n); i++ }
                '\r' -> { i++; if (i < raw.length && raw[i] == '\n') i++ }
                '\n' -> i++
                in '0'..'7' -> {
                    var v = 0
                    var k = 0
                    while (k < 3 && i < raw.length && raw[i] in '0'..'7') { v = v * 8 + (raw[i] - '0'); i++; k++ }
                    out.append((v and 0xff).toChar())
                }
                else -> { out.append(n); i++ }
            }
        }
        return out.toString()
    }
}
