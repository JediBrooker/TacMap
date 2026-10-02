package com.tacmap.calibration.fiducial

import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.PlaneAffine
import com.tacmap.map.render.MapCamera
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * testdata/calibration_fit_report.json: the fit panel (cases), the far warning
 * (entryChecks), the refit camera anchor and capture. Same file as iOS.
 */
class CalibrationFitReportTest {
    private val root = Json.parseToJsonElement(PdfGeorefFixture.file("calibration_fit_report.json").readText()).jsonObject
    private val tol = root["tolerance"]!!.jsonObject
    private val tolM = tol["metres"]!!.jsonPrimitive.double
    private val tolRatio = tol["ratio"]!!.jsonPrimitive.double
    private val tolDeg = tol["degrees"]!!.jsonPrimitive.double
    private val tolZoom = tol["zoom"]!!.jsonPrimitive.double
    private val tolPage = tol["pagePoints"]!!.jsonPrimitive.double
    private val tolAffine = tol["affineRelative"]!!.jsonPrimitive.double
    private val cases = root["cases"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("id")!! }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.n(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.o(k: String): JsonObject? = this[k]?.takeIf { it !is JsonNull }?.jsonObject

    private fun pagePoints(e: kotlinx.serialization.json.JsonElement): List<PagePoint> =
        e.jsonArray.map { PdfGeorefFixture.point(it) }

    private fun reference(o: JsonObject): CalibrationReference {
        o.o("grid")?.let { g ->
            return CalibrationReference.Grid(
                zone = g.i("zone")!!,
                south = (g["south"] as JsonPrimitive).booleanOrNull!!,
                easting = g.n("easting")!!,
                northing = g.n("northing")!!,
                cellSizeM = g.n("cellSizeM") ?: 1.0,
                source = if (g.s("source") == "utm") GridSource.UTM else GridSource.MGRS,
            )
        }
        val geo = o.o("geographic")!!
        return CalibrationReference.Geographic(geo.n("lat")!!, geo.n("lon")!!)
    }

    private fun point(p: JsonObject, number: Int = p.i("number")!!): CalibrationPoint = CalibrationPoint(
        id = "p$number",
        number = number,
        page = StoredPagePoint.of(PdfGeorefFixture.point(p["page"]!!)),
        input = p.s("input") ?: "",
        reference = reference(p.o("reference")!!),
        kind = CalibrationPointKind.fromCode(p.s("kind")) ?: CalibrationPointKind.INTERSECTION,
        datumOverride = p.s("datumOverride"),
    )

    private fun sheetOf(c: JsonObject) = CalibrationSheet(
        datum = requireNotNull(GeoDatums.byId(c.s("datumId")!!)),
        pageBox = pagePoints(c["pageBox"]!!),
        rotate = c.i("rotate") ?: 0,
    )

    private fun georef(o: JsonObject): PdfGeoreference = PdfGeoreference(
        page = 0,
        crs = PdfGeorefFixture.crsOf(o.o("crs")!!),
        datum = PdfGeorefFixture.datumOf(o.o("datum")!!),
        affine = PlaneAffine.of(PdfGeorefFixture.doubles(o["affine"]!!))!!,
        crop = pagePoints(o["crop"]!!),
        origin = GeorefOrigin.fromCode(o.s("origin")!!)!!,
    )

    private fun assertMessage(label: String, want: JsonObject, have: StatusMessage) {
        assertEquals("$label key", want.s("key"), have.key)
        val args = want.o("args")!!
        assertEquals("$label arg names", args.keys, have.args.keys)
        args.forEach { (k, v) ->
            val p = v.jsonPrimitive
            val a = have.args.getValue(k)
            if (p.isString) assertEquals("$label $k", p.content, a.toString())
            else assertEquals("$label $k", p.double, (a as Number).toDouble(), tolM)
        }
    }

    private fun assertMap(label: String, want: JsonObject?, have: Map<Int, Double?>) {
        if (want == null) {
            assertTrue("$label should be empty", have.isEmpty())
            return
        }
        assertEquals(label, want.keys.map { it.toInt() }.toSet(), have.keys)
        want.forEach { (k, v) ->
            val w = (v as? JsonPrimitive)?.doubleOrNull
            val h = have[k.toInt()]
            if (w == null) assertNull("$label[$k]", h) else assertEquals("$label[$k]", w, h!!, tolM)
        }
    }

    private fun near(label: String, want: Double?, have: Double?, abs: Double) {
        if (want == null) assertNull(label, have) else assertEquals(label, want, have!!, abs)
    }

    @Test
    fun everyCaseClassifiesLikeTheSharedFixture() {
        // 29 at r1, the two lon +-180 cases among them
        assertTrue("only ${cases.size} cases", cases.size >= 29)
        assertTrue("first_point_lon_180_zone_60" in cases && "first_point_lon_minus_180_zone_1" in cases)
        for ((id, c) in cases) {
            val sheet = sheetOf(c)
            val pts = c["points"]!!.jsonArray.map { point(it.jsonObject) }
            val r = CalibrationFitReport.evaluate(sheet, pts)
            val e = c.o("expect")!!
            assertEquals("$id n", e.i("n"), r.n)
            assertEquals("$id planeZone", e.i("planeZone"), r.planeZone)
            assertEquals("$id south", (e["south"] as? JsonPrimitive)?.booleanOrNull, r.south)
            e.n("eigenRatio")?.let { assertEquals("$id eigen", it, r.eigenRatio!!, tolRatio * maxOf(1e-12, it) + 1e-12) }
                ?: assertNull("$id eigen", r.eigenRatio)
            if (e["affine"] is JsonNull) {
                assertNull("$id affine", r.affine)
            } else {
                PdfGeorefFixture.doubles(e["affine"]!!).zip(r.affine!!.coefficients).forEachIndexed { i, (w, h) ->
                    assertEquals("$id affine[$i]", w, h, tolAffine * maxOf(1.0, abs(w)))
                }
            }
            near("$id diagonal", e.n("diagonalM"), r.diagonalM, tolM)
            near("$id tau", e.n("toleranceM"), r.toleranceM, tolM)
            near("$id rms", e.n("rmsM"), r.rmsM, tolM)
            near("$id max", e.n("maxM"), r.maxM, tolM)
            assertMap("$id residuals", e.o("residualsM"), r.residualsM)
            assertMap("$id looRms", e.o("looRmsM"), r.looRmsM)
            assertMap("$id loo", e.o("looM"), r.looM)
            e.n("anisotropy")?.let { assertEquals("$id aniso", it, r.anisotropy!!, tolRatio * it) } ?: assertNull(id, r.anisotropy)
            e.n("scaleDenominator")?.let { assertEquals("$id scale", it, r.scaleDenominator!!, tolRatio * it) }
                ?: assertNull(id, r.scaleDenominator)
            assertEquals("$id grade", e.s("grade"), r.grade?.code)
            assertEquals("$id exact", (e["exact"] as JsonPrimitive).booleanOrNull, r.exact)
            val issue = e.o("issue")
            when (issue?.s("type")) {
                null -> assertNull("$id issue", r.issue)
                "outlier" -> {
                    val o = r.issue as FitIssue.Outlier
                    assertEquals(id, issue.i("number"), o.number)
                    assertEquals(id, issue.n("distanceM")!!, o.distanceM, tolM)
                }
                "ambiguous" -> {
                    val a = r.issue as FitIssue.Ambiguous
                    assertEquals(id, issue.i("first"), a.first)
                    assertEquals(id, issue.i("second"), a.second)
                }
                "disagree" -> assertEquals(id, issue.n("maxResidualM")!!, (r.issue as FitIssue.Disagree).maxResidualM, tolM)
                "disagreeAddFifth" -> assertEquals(id, issue.n("maxResidualM")!!, (r.issue as FitIssue.DisagreeAddFifth).maxResidualM, tolM)
                else -> error("unknown issue in $id")
            }
            assertEquals("$id flagged", e["flagged"]!!.jsonArray.map { it.jsonPrimitive.int }, r.flagged)
            val rows = e.o("rowStatus")
            if (rows == null) assertNull("$id rows", r.rowStatus)
            else assertEquals("$id rows", rows.mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.content }, r.rowStatus!!.mapValues { it.value.code })
            assertEquals("$id spreadLow", (e["spreadLow"] as JsonPrimitive).booleanOrNull, r.spreadLow)
            assertEquals("$id nextCorner", e.s("nextCorner"), r.nextCorner?.code)
            if (e["nextCornerPage"] is JsonNull) assertNull(id, r.nextCornerPage) else {
                val p = PdfGeorefFixture.point(e["nextCornerPage"]!!)
                assertEquals("$id corner x", p.x, r.nextCornerPage!!.x, 1e-6)
                assertEquals("$id corner y", p.y, r.nextCornerPage!!.y, 1e-6)
            }
            val finish = e.o("finish")!!
            when (finish.s("state")) {
                "blocked" -> {
                    val b = r.finish as Finishability.Blocked
                    assertEquals("$id blocked", finish.s("reason"), b.reason.code)
                    finish.i("needMore")?.let { assertEquals("$id needMore", it, b.needMore) }
                }
                "confirm" -> assertEquals(
                    "$id confirm",
                    finish["reasons"]!!.jsonArray.map { it.jsonPrimitive.content },
                    (r.finish as Finishability.Confirm).reasons.map { it.code },
                )
                "ready" -> assertEquals("$id ready", Finishability.Ready, r.finish)
            }
            assertMessage("$id primary", e.o("primaryStatus")!!, r.primaryStatus)
            val confirm = e["confirmMessages"]!!.jsonArray.map { it.jsonObject }
            assertEquals("$id confirm count", confirm.size, r.confirmMessages.size)
            confirm.zip(r.confirmMessages).forEach { (w, h) -> assertMessage("$id confirm", w, h) }
            assertPanelLines(id, e, r)
            // hold-outs + checks: the fit puts printed points where PROJ says
            (c["holdOut"]?.jsonArray.orEmpty() + c["checks"]?.jsonArray.orEmpty()).map { it.jsonObject }.forEach { h ->
                val g = requireNotNull(r.fitGeoref) { "$id georef" }
                val p = PdfGeorefFixture.point(h["page"]!!)
                val w = requireNotNull(g.toWGS84(p.x, p.y))
                val f = h.o("fitWGS84")!!
                assertEquals("$id ${h.s("label")} lat", f.n("lat")!!, w.latitude, tolDeg)
                assertEquals("$id ${h.s("label")} lon", f.n("lon")!!, w.longitude, tolDeg)
            }
        }
    }

    /**
     * B2 + OD-F1 through the same pure function the panel draws from: expect.secondaryHint
     * with no camera, the zoom hint only on sheet, off sheet always line 1 (also when the
     * camera's too far off for the georef to give a page point at all)
     */
    private fun assertPanelLines(id: String, e: JsonObject, r: FitReport) {
        val hint = e.o("secondaryHint")
        val bare = CalibrationPanelStatus.of(r, capture = null, cameraKnown = false)
        assertFalse("$id offSheet", bare.offSheet)
        assertEquals("$id panel primary", r.primaryStatus, bare.primary)
        if (hint == null) assertNull("$id secondary", bare.secondary) else assertMessage("$id secondary", hint, bare.secondary!!)

        val zoomed = CalibrationPanelStatus.of(r, CalibrationCapture(PagePoint(1.0, 1.0), true, 0.5, true))
        assertEquals("$id zoom hint primary", r.primaryStatus, zoomed.primary)
        assertEquals("$id zoom hint replaces", "calibration_zoom_hint", zoomed.secondary?.key)

        val off = CalibrationPanelStatus.of(r, CalibrationCapture(PagePoint(-1.0, -1.0), false, 0.5, true))
        assertTrue("$id off", off.offSheet)
        assertEquals("$id off primary", "calibration_off_sheet", off.primary.key)
        // no zoom hint off the sheet, the corner hint stays
        assertEquals("$id off secondary", hint?.s("key"), off.secondary?.key)

        val far = CalibrationPanelStatus.of(r, capture = null, cameraKnown = true)
        assertTrue("$id far", far.offSheet)
        assertEquals("$id far primary", "calibration_off_sheet", far.primary.key)
    }

    @Test
    fun entryChecksWarnOnlyWhereTheFixtureDoes() {
        val checks = root["entryChecks"]!!.jsonArray.map { it.jsonObject }
        assertTrue(checks.size >= 8)
        for (c in checks) {
            val id = c.s("id")!!
            val from = cases.getValue(c.s("fromCase")!!)
            val sheet = sheetOf(from)
            val pts = from["points"]!!.jsonArray.map { point(it.jsonObject) }
            val editing = c.i("editing")
            val pend = c.o("pending")!!
            val pending = point(pend, editing ?: ((pts.maxOfOrNull { it.number } ?: 0) + 1))
            val base = c.o("base")?.let { b -> georef(b.o("georef")!!) }
            val got = CalibrationFitReport.entryCheck(sheet, pts, editing, pending, base)
            val e = c.o("expect")!!
            assertEquals("$id predictor", e.s("predictor"), got.predictor.code)
            near("$id leverage", e.n("leverage"), got.leverage, 1e-6)
            near("$id tau", e.n("toleranceM"), got.toleranceM, tolM)
            near("$id distance", e.n("distanceM"), got.distanceM, tolM)
            near("$id threshold", e.n("thresholdM"), got.thresholdM, tolM)
            assertEquals("$id warn", (e["warn"] as JsonPrimitive).booleanOrNull, got.warn)
            e.o("typedWGS84")?.let {
                assertEquals("$id typed lat", it.n("lat")!!, got.typed!!.latitude, tolDeg)
                assertEquals("$id typed lon", it.n("lon")!!, got.typed!!.longitude, tolDeg)
            }
            e.o("predictedWGS84")?.let {
                assertEquals("$id predicted lat", it.n("lat")!!, got.predicted!!.latitude, tolDeg)
                assertEquals("$id predicted lon", it.n("lon")!!, got.predicted!!.longitude, tolDeg)
            } ?: assertNull("$id predicted", got.predicted)
            val msg = e.o("message")
            if (msg == null) assertNull("$id message", got.message) else assertMessage("$id message", msg, got.message!!)
        }
    }

    @Test
    fun cameraAnchorKeepsThePagePointAndScale() {
        val anchors = root["cameraAnchor"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, anchors.size)
        for (c in anchors) {
            val id = c.s("id")!!
            var old = georef(c.o("old")!!)
            c.o("provisionalInputs")?.let { pi ->
                // the app builds the provisional with PdfGeoreference.provisional, check it IS the fixture's
                val rebuilt = requireNotNull(PdfGeoreference.provisional(
                    com.tacmap.calibration.Wgs84Coordinate(pi.o("centre")!!.n("lat")!!, pi.o("centre")!!.n("lon")!!),
                    pagePoints(pi["pageBox"]!!),
                    pi.i("rotate") ?: 0,
                ))
                rebuilt.affine.coefficients.zip(old.affine.coefficients).forEachIndexed { i, (h, w) ->
                    assertEquals("$id provisional affine[$i]", w, h, 1e-9 * maxOf(1.0, abs(w)))
                }
                old = rebuilt
            }
            val new = georef(c.o("new")!!)
            val cam = c.o("camera")!!
            val vp = PdfGeorefFixture.doubles(cam["viewport"]!!)
            val camera = MapCamera(cam.n("lat")!!, cam.n("lon")!!, cam.n("zoom")!!, cam.n("heading")!!, vp[0], vp[1])
            val e = c.o("expect")!!
            val p = requireNotNull(old.toPage(camera.centerLat, camera.centerLon))
            val wantPage = PdfGeorefFixture.point(e["pagePoint"]!!)
            assertEquals("$id page x", wantPage.x, p.x, tolPage)
            assertEquals("$id page y", wantPage.y, p.y, tolPage)
            val adjusted = requireNotNull(CalibrationCameraAnchor.adjust(camera, old, new)) { id }
            assertEquals("$id lat", e.n("lat")!!, adjusted.centerLat, tolDeg)
            assertEquals("$id lon", e.n("lon")!!, adjusted.centerLon, tolDeg)
            assertEquals("$id zoom", e.n("zoom")!!, adjusted.zoom, tolZoom)
            assertEquals("$id unclamped", e.n("unclampedZoom")!!, CalibrationCameraAnchor.unclampedZoom(camera, old, new)!!, tolZoom)
            assertEquals("$id heading", e.n("heading")!!, adjusted.headingDegrees, 0.0)
            // and the whole point of it: the same page point sits under the crosshair after
            val back = requireNotNull(new.toPage(adjusted.centerLat, adjusted.centerLon))
            assertEquals("$id back x", p.x, back.x, 1e-6)
            assertEquals("$id back y", p.y, back.y, 1e-6)
        }
    }

    @Test
    fun captureReadsThePageUnderTheCrosshair() {
        val caps = root["capture"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, caps.size)
        for (c in caps) {
            val id = c.s("id")!!
            val g = georef(c.o("georef")!!)
            val sheet = CalibrationSheet(GeoDatums.WGS84, pagePoints(c["pageBox"]!!))
            val cam = c.o("camera")!!
            val camera = MapCamera(cam.n("lat")!!, cam.n("lon")!!, cam.n("zoom")!!, cam.n("heading")!!, 390.0, 844.0)
            val got = requireNotNull(CalibrationCameraAnchor.capture(g, sheet, camera)) { id }
            val e = c.o("expect")!!
            val p = PdfGeorefFixture.point(e["page"]!!)
            assertEquals("$id x", p.x, got.page.x, tolPage)
            assertEquals("$id y", p.y, got.page.y, tolPage)
            assertEquals("$id onSheet", (e["onSheet"] as JsonPrimitive).booleanOrNull, got.onSheet)
            assertEquals("$id spp", e.n("screenPtPerPagePt")!!, got.screenPointsPerPagePoint!!, tolRatio * e.n("screenPtPerPagePt")!!)
            assertEquals("$id hint", (e["zoomHint"] as JsonPrimitive).booleanOrNull, got.zoomHint)
            val status = e.o("status")
            if (status == null) assertNull("$id status", got.status) else assertEquals("$id status", status.s("key"), got.status!!.key)
        }
    }

    @Test
    fun messageKeysTableMatchesTheEnums() {
        val keys = root["messageKeys"]!!.jsonObject
        BlockReason.entries.filter { it.messageKey != null }.forEach {
            assertEquals(it.code, keys.o("blocked")!!.s(it.code), it.messageKey)
        }
        FitGrade.entries.forEach { assertEquals(it.code, keys.o("grade")!!.s(it.code), it.messageKey) }
        PageCorner.entries.forEach { assertEquals(it.code, keys.o("nextCorner")!!.s(it.code), it.messageKey) }
        assertNotNull(keys.s("spreadLow"))
        assertFalse(keys.isEmpty())
    }
}
