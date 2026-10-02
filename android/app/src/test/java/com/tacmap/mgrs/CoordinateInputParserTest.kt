package com.tacmap.mgrs

import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.calibration.fiducial.CalibrationPointKind
import com.tacmap.calibration.fiducial.resolved
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** testdata/calibration_input.json, every case through the real parser (iOS loads the same file) */
class CoordinateInputParserTest {
    private val root = Json.parseToJsonElement(PdfGeorefFixture.file("calibration_input.json").readText()).jsonObject
    private val tolM = root["tolerance"]!!.jsonObject["metres"]!!.jsonPrimitive.double
    private val tolDeg = root["tolerance"]!!.jsonObject["degrees"]!!.jsonPrimitive.double

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.n(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private fun context(c: JsonObject): CoordinateParseContext {
        val ctx = c["context"]!!.jsonObject
        val anchor = ctx["gridAnchor"]?.takeIf { it !is JsonNull }?.jsonObject?.let {
            GridAnchor(it.i("zone")!!, it.s("band")!![0], it.s("square")!!, it.i("fromPoint")!!)
        }
        val predicted = ctx["predicted"]?.takeIf { it !is JsonNull }?.jsonObject?.let {
            Wgs84Coordinate(it.n("lat")!!, it.n("lon")!!)
        }
        return CoordinateParseContext(
            datum = requireNotNull(GeoDatums.byId(ctx.s("datumId")!!)),
            isFirstPoint = (ctx["isFirstPoint"] as JsonPrimitive).booleanOrNull ?: true,
            kind = requireNotNull(CalibrationPointKind.fromCode(ctx.s("kind"))),
            gridAnchor = anchor,
            predicted = predicted,
        )
    }

    @Test
    fun everyCaseParsesExactlyAsTheSharedFixture() {
        val cases = root["cases"]!!.jsonArray.map { it.jsonObject }
        // 105 at r1, a regen that loses cases fails here instead of quietly testing less
        assertTrue("only ${cases.size} cases", cases.size >= 105)
        val ids = cases.map { it.s("id") }.toSet()
        listOf("utm_leading_zero_figures", "dd_lon_exactly_180", "dd_lon_exactly_minus_180").forEach {
            assertTrue("missing $it", it in ids)
        }
        val errorKeys = root["errorKeys"]!!.jsonObject
        CoordinateParseError.entries.forEach { e ->
            assertTrue(e.code, e.code in errorKeys)
            assertEquals(e.code, (errorKeys[e.code] as? JsonPrimitive)?.contentOrNull, e.messageKey)
        }
        for (c in cases) {
            val id = c.s("id")!!
            val ctx = context(c)
            val got = CoordinateInputParser.parse(c.s("input")!!, ctx)
            val expect = c["expect"]!!.jsonObject
            val err = expect.s("error")
            if (err != null) {
                assertTrue("$id should fail with $err, got $got", got is ParseOutcome.Err)
                got as ParseOutcome.Err
                assertEquals(id, err, got.error.code)
                assertEquals("$id key", expect.s("messageKey"), got.error.messageKey)
                val args = expect["args"]!!.jsonObject.mapValues { (it.value as JsonPrimitive).content }
                assertEquals("$id args", args, got.args)
                continue
            }
            assertTrue("$id should parse, got $got", got is ParseOutcome.Ok)
            val r = (got as ParseOutcome.Ok).reference
            val ok = expect["ok"]!!.jsonObject
            assertEquals("$id source", ok.s("source"), r.source.code)
            assertEquals("$id zone", ok.i("zone"), r.zone)
            assertEquals("$id south", (ok["south"] as? JsonPrimitive)?.booleanOrNull, r.south)
            assertEquals("$id band", ok.s("band"), r.band?.toString())
            assertEquals("$id square", ok.s("square"), r.square)
            ok.n("easting")?.let { assertEquals("$id E", it, r.easting!!, tolM) } ?: assertNull(id, r.easting)
            ok.n("northing")?.let { assertEquals("$id N", it, r.northing!!, tolM) } ?: assertNull(id, r.northing)
            assertEquals("$id digits", ok.i("digits"), r.digits)
            ok.n("cellSizeM")?.let { assertEquals("$id cell", it, r.cellSizeM!!, 0.0) } ?: assertNull(id, r.cellSizeM)
            assertEquals("$id effectiveKind", ok.s("effectiveKind"), r.effectiveKind?.code)
            assertEquals("$id kindSegment", (ok["kindSegment"] as JsonPrimitive).booleanOrNull, r.kindSegment)
            ok.n("lat")?.let { assertEquals("$id lat", it, r.latitude!!, tolDeg) } ?: assertNull(id, r.latitude)
            ok.n("lon")?.let { assertEquals("$id lon", it, r.longitude!!, tolDeg) } ?: assertNull(id, r.longitude)
            assertEquals("$id completedFrom", ok.s("completedFrom"), r.completedFrom?.code)
            assertEquals("$id completedFromPoint", ok.i("completedFromPoint"), r.completedFromPoint)
            assertEquals("$id canonical", ok.s("canonical"), r.canonical)
            val res = ok["resolved"]!!.jsonObject
            assertEquals("$id resolved lat", res.n("lat")!!, r.resolvedLatitude, tolDeg)
            assertEquals("$id resolved lon", res.n("lon")!!, r.resolvedLongitude, tolDeg)
            val w = ok["resolvedWGS84"]!!.jsonObject
            val wgs = requireNotNull(ctx.datum.toWGS84(r.resolvedLatitude, r.resolvedLongitude))
            assertEquals("$id wgs lat", w.n("lat")!!, wgs.latitude, tolDeg)
            assertEquals("$id wgs lon", w.n("lon")!!, wgs.longitude, tolDeg)
            val wantMessages = ok["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals("$id message count", wantMessages.size, r.messages.size)
            wantMessages.zip(r.messages).forEach { (want, have) ->
                assertEquals("$id message", want.s("key"), have.key)
                val wantArgs = want["args"]!!.jsonObject
                assertEquals("$id ${have.key} arg names", wantArgs.keys, have.args.keys)
                wantArgs.forEach { (k, v) ->
                    val p = v.jsonPrimitive
                    val a = have.args[k]
                    if (p.isString) assertEquals("$id $k", p.content, a.toString())
                    else assertEquals("$id $k", p.double, (a as Number).toDouble(), 1e-9)
                }
            }
            // the round trip: the typed reference resolves the same through the stored model
            val ref = r.toReference()
            assertEquals("$id grid?", r.source != CoordinateSource.LAT_LON, ref is com.tacmap.calibration.fiducial.CalibrationReference.Grid)
            // built the way the controller saves a point, then resolved the way the fit reads
            // it back: has to land on the fixture's resolved lat/lon (kind offset included)
            val stored = com.tacmap.calibration.fiducial.CalibrationPoint(
                id = id, number = 1,
                page = com.tacmap.calibration.fiducial.StoredPagePoint(0.0, 0.0),
                input = c.s("input")!!, reference = ref,
                kind = r.effectiveKind ?: CalibrationPointKind.INTERSECTION,
                canonical = r.canonical,
            )
            val back = requireNotNull(stored.resolved(ctx.datum)) { "$id didn't resolve" }
            assertEquals("$id stored lat", res.n("lat")!!, back.latitude, tolDeg)
            assertEquals("$id stored lon", res.n("lon")!!, back.longitude, tolDeg)
            c["truthWGS84"]?.jsonObject?.let { t ->
                val d = PdfGeorefFixture.metres(t.n("lat")!!, t.n("lon")!!, wgs.latitude, wgs.longitude)
                assertTrue("$id truth $d m", d <= c.n("truthToleranceM")!!)
            }
        }
    }

    @Test
    fun columnSetCheckMatchesIosSafeMgrs() {
        // 56 % 3 == 2: J-R. A isn't a column there (D2-11 #3)
        assertNull(MgrsFormatter.parse("56HAH3490052288"))
        assertTrue(MgrsFormatter.parse("56HLH3490052288") != null)
        assertTrue(!MgrsFormatter.looksLikeMgrs("56HAH3490052288"))
        assertEquals("ABCDEFGH", CoordinateInputParser.columnLetters(1))
        assertEquals("JKLMNPQR", CoordinateInputParser.columnLetters(56))
        assertEquals("STUVWXYZ", CoordinateInputParser.columnLetters(3))
        // even zones shift the row letters by 5
        assertEquals("FGHJKLMNPQRSTUVABCDE", CoordinateInputParser.rowLetters(56))
        assertEquals("LH", CoordinateInputParser.squareOf(56, 334_900.0, 6_252_288.0))
        val limits = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject
        assertEquals(
            limits["calibration"]!!.jsonObject["maxInputUtf16Units"]!!.jsonPrimitive.int,
            CoordinateInputParser.MAX_INPUT_UTF16_UNITS,
        )
    }
}
