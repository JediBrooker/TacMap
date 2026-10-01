package com.tacmap.mgrs

import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.WebMercator
import com.tacmap.mgrs.MgrsGridRenderer.Axis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mil.nga.mgrs.grid.GridType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Pins the grid overlay to testdata/mgrs_grid.json (shared with iOS, expected
 * values from PROJ). One test per fixture section, see testdata/README.md
 * "MGRS grid overlay contract" for what each one asks.
 */
class MgrsGridFixtureTest {

    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/mgrs_grid.json")
            if (file.exists()) return@lazy Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/mgrs_grid.json")
    }
    private val policy get() = fixture["policy"]!!.jsonObject

    private fun JsonObject.d(key: String): Double = this[key]!!.jsonPrimitive.double
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.int
    private fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.b(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
    private fun JsonObject.arr(key: String): JsonArray = this[key]!!.jsonArray
    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
    private fun JsonArray.dbl(i: Int): Double = this[i].jsonPrimitive.double
    private fun JsonObject.levels(key: String): List<GridType> = arr(key).map { level(it.jsonPrimitive.content) }
    private fun JsonObject.south(): Boolean = s("hemisphere") == "S"
    private fun JsonObject.axis(): Axis = if (s("axis") == "easting") Axis.EASTING else Axis.NORTHING

    private fun level(name: String): GridType = when (name) {
        "100km" -> GridType.HUNDRED_KILOMETER
        "10km" -> GridType.TEN_KILOMETER
        "1km" -> GridType.KILOMETER
        else -> error("unknown level $name")
    }

    // ---- policy ----

    @Test
    fun policyConstantsMatchFixture() {
        val levels = policy.arr("levels").map { it.jsonObject }
        assertEquals(levels.map { level(it.s("name")) }, MgrsGridRenderer.LEVELS)
        assertEquals(levels.map { it.i("metres") }, MgrsGridRenderer.LEVELS.map { MgrsGridRenderer.metres(it) })
        assertEquals(policy.d("tileSizeDp"), MgrsGridRenderer.TILE_SIZE_DP, 0.0)
        assertEquals(policy.d("tileSizeDp"), WebMercator.TILE_SIZE, 0.0)
        assertEquals(policy.d("earthRadiusMetres"), MgrsGridRenderer.EARTH_RADIUS_METRES, 0.0)
        assertEquals(policy.d("mercatorLatLimit"), WebMercator.LAT_LIMIT, 1e-12)
        assertEquals(policy.d("lineMinDp"), MgrsGridRenderer.LINE_MIN_DP, 0.0)
        assertEquals(policy.d("labelMinDp"), MgrsGridRenderer.LABEL_MIN_DP, 0.0)
        assertEquals(policy.d("labelInsetDp"), MgrsGridRenderer.LABEL_INSET_DP, 0.0)
        assertEquals(policy.d("squareLabelMinDp"), MgrsGridRenderer.SQUARE_LABEL_MIN_DP, 0.0)
        assertEquals(policy.d("squareLabelOffsetDp"), MgrsGridRenderer.SQUARE_LABEL_OFFSET_DP, 0.0)
        assertEquals(policy.d("maxSagittaPx"), MgrsGridRenderer.MAX_SAGITTA_PX, 0.0)
        assertEquals(policy.arr("gridLatRange").dbl(0), MgrsGridRenderer.GRID_LAT_MIN, 0.0)
        assertEquals(policy.arr("gridLatRange").dbl(1), MgrsGridRenderer.GRID_LAT_MAX, 0.0)
        assertEquals(policy.s("bands"), MgrsGridRenderer.BANDS)
        assertEquals(policy.s("rowLetters"), MgrsGridRenderer.ROW_LETTERS)
        assertEquals(policy.i("evenZoneRowShift"), MgrsGridRenderer.EVEN_ZONE_ROW_SHIFT)

        val cache = policy.obj("cache")
        assertEquals(cache.d("rebuildZoomDelta"), MgrsGridBuildSpec.REBUILD_ZOOM_DELTA, 0.0)
        assertEquals(cache.d("rebuildCentreMoveDp"), MgrsGridBuildSpec.REBUILD_CENTRE_MOVE_DP, 0.0)
        assertEquals(cache.d("coverageMarginDp"), MgrsGridBuildSpec.COVERAGE_MARGIN_DP, 0.0)

        // look is unchanged: same widths + text sizes the fixture pins
        val style = policy.obj("style")
        for ((name, w) in style.obj("lineWidthDp")) {
            assertEquals(name, w.jsonPrimitive.double, MgrsGridRenderer.lineWidthDp(level(name)).toDouble(), 1e-6)
        }
        for ((name, sp) in style.obj("labelTextSize")) {
            assertEquals(name, sp.jsonPrimitive.double, MgrsGridRenderer.labelTextSp(level(name)).toDouble(), 1e-6)
        }
    }

    @Test
    fun cellSpansMatchFixturePolicy() {
        val bandH = policy.d("bandHeightDegrees")
        for ((i, band) in MgrsGridRenderer.BANDS.withIndex()) {
            val c = MgrsGridRenderer.cell(1, band)!!
            assertEquals("$band south", -80.0 + bandH * i, c.latS, 0.0)
            val north = if (band == 'X') policy.arr("bandXLat").dbl(1) else c.latS + bandH
            assertEquals("$band north", north, c.latN, 0.0)
        }
        assertEquals(policy.arr("bandXLat").dbl(0), MgrsGridRenderer.cell(1, 'X')!!.latS, 0.0)
        for ((gzd, span) in policy.obj("zoneSpanExceptions")) {
            val cell = MgrsGridRenderer.cell(gzd.dropLast(1).toInt(), gzd.last())
            if (span is JsonNull) {
                assertNull(gzd, cell)
            } else {
                assertNotNull(gzd, cell)
                assertEquals(gzd, span.jsonArray.dbl(0), cell!!.lonW, 0.0)
                assertEquals(gzd, span.jsonArray.dbl(1), cell.lonE, 0.0)
            }
        }
        // plain strip rule everywhere else
        val t32 = MgrsGridRenderer.cell(32, 'T')!!
        assertEquals(6.0, t32.lonW, 0.0)
        assertEquals(12.0, t32.lonE, 0.0)

        val cols = policy.obj("columnLetters")
        for ((zone, key) in listOf(1 to "zoneMod3Is1", 2 to "zoneMod3Is2", 3 to "zoneMod3Is0")) {
            val letters = (1..8).joinToString("") {
                MgrsGridRenderer.squareLetters(zone, it * 100_000, 0).first().toString()
            }
            assertEquals(key, cols.s(key), letters)
        }
    }

    // ---- lod ----

    @Test
    fun levelOfDetailIsInDpAndMatchesFixture() {
        for (e in fixture.arr("lod")) {
            val c = e.jsonObject
            val name = c.s("name")
            val lod = MgrsGridRenderer.lod(c.d("zoom"), c.d("lat"))
            for ((lvl, sp) in c.obj("spacingDp")) {
                assertEquals("$name $lvl spacing", sp.jsonPrimitive.double, lod.spacingDp.getValue(level(lvl)), 1e-3)
            }
            assertEquals("$name drawn", c.levels("drawn"), lod.drawn)
            assertEquals("$name labelled", c.levels("labelled"), lod.labelled)
        }
    }

    // ---- label text ----

    @Test
    fun lineLabelIsTheLinesOwnValue() {
        for (e in fixture.arr("lineLabels")) {
            val c = e.jsonObject
            val finest = level(c.s("finestLabelledLevel"))
            val labelled = MgrsGridRenderer.LEVELS.take(MgrsGridRenderer.LEVELS.indexOf(finest) + 1)
            val expected = c["text"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(c.s("note"), expected, MgrsGridRenderer.lineLabelText(c.i("value"), labelled))
        }
    }

    @Test
    fun squareLettersMatchFixture() {
        for (e in fixture.arr("squareLabels")) {
            val c = e.jsonObject
            assertEquals(
                c.s("note"), c.s("text"),
                MgrsGridRenderer.squareLetters(c.i("zone"), c.i("easting"), c.i("northing")),
            )
        }
    }

    // ---- geometry ----

    @Test
    fun geometryHugsTheProjCurveWithinQuarterPixel() {
        val vertexTol = policy.d("vertexTolMetres")
        for (e in fixture.arr("geometry")) {
            val c = e.jsonObject
            val name = c.s("name")
            val bounds = c.obj("bounds")
            val line = c.obj("line")
            val lod = MgrsGridRenderer.lod(c.d("zoom"), c.d("lodLat"))
            val geometry = MgrsGridRenderer.build(
                bounds.d("south"), bounds.d("west"), bounds.d("north"), bounds.d("east"),
                drawn = lod.drawn, withSquares = false,
                densifyZoom = c.d("zoom"), pxPerDp = c.d("pxPerDp"),
            )
            val key = MgrsGridRenderer.LineKey(line.i("zone"), line.south(), line.axis(), line.i("value"))
            val pieces = geometry.piecesByKey[key].orEmpty()
            assertTrue("$name: no pieces for $key", pieces.isNotEmpty())
            pieces.forEach { assertEquals("$name owner level", level(line.s("level")), it.level) }

            val tol = c.d("tolMetres")
            val ours = pieces.map { p -> p.latitudes.indices.map { doubleArrayOf(p.latitudes[it], p.longitudes[it]) } }
            val refs = c.arr("pieces").map { p -> p.jsonObject.arr("samples").map { doubleArrayOf(it.jsonArray.dbl(0), it.jsonArray.dbl(1)) } }
            val utm = DoubleArray(2)
            for (samples in refs) {
                for (s in samples) {
                    val d = ours.minOf { polylineDistance(s, it) }
                    assertTrue("$name: sample ${s.toList()} is ${d}m off (tol $tol)", d <= tol)
                    // the TM itself: sample is PROJ's, so it has to land on the line value
                    UtmProjection.forward(key.zone, key.south, s[0], s[1], utm)
                    val v = if (key.axis == Axis.EASTING) utm[0] else utm[1]
                    assertEquals("$name TM vs PROJ", key.value.toDouble(), v, vertexTol)
                }
            }
            val along = if (key.axis == Axis.EASTING) 0 else 1
            val lo = refs.flatten().minOf { it[along] }
            val hi = refs.flatten().maxOf { it[along] }
            for (poly in ours) {
                for (v in poly) {
                    if (v[along] < lo || v[along] > hi) continue
                    val d = refs.minOf { polylineDistance(v, it) }
                    assertTrue("$name: vertex ${v.toList()} is ${d}m off (tol $tol)", d <= tol)
                }
            }
        }
    }

    // ---- clip ----

    @Test
    fun linesStayInsideTheirOwnCellAndNeverDouble() {
        val eps = policy.d("clipEpsilonDegrees")
        for (e in fixture.arr("clip")) {
            val c = e.jsonObject
            val name = c.s("name")
            val bounds = c.obj("bounds")
            val lod = MgrsGridRenderer.lod(c.d("zoom"), c.d("lodLat"))
            assertEquals("$name drawn", c.levels("drawn"), lod.drawn)
            for (cellJson in c.arr("cells")) {
                val cj = cellJson.jsonObject
                val cell = MgrsGridRenderer.cell(cj.i("zone"), cj.s("band").single())!!
                assertEquals(cj.s("gzd"), cell.gzd)
                assertEquals(cj.arr("lon").dbl(0), cell.lonW, 0.0)
                assertEquals(cj.arr("lon").dbl(1), cell.lonE, 0.0)
                assertEquals(cj.arr("lat").dbl(0), cell.latS, 0.0)
                assertEquals(cj.arr("lat").dbl(1), cell.latN, 0.0)
            }
            val geometry = MgrsGridRenderer.build(
                bounds.d("south"), bounds.d("west"), bounds.d("north"), bounds.d("east"),
                drawn = lod.drawn, withSquares = false,
                densifyZoom = c.d("zoom"), pxPerDp = c.d("pxPerDp"),
            )

            // every point inside its own cell's span
            for (p in geometry.pieces) {
                for (i in p.latitudes.indices) {
                    val lat = p.latitudes[i]
                    val lon = p.longitudes[i]
                    assertTrue(
                        "$name: ${p.key} in ${p.cell.gzd} leaves its cell at $lat,$lon",
                        lat >= p.cell.latS - eps && lat <= p.cell.latN + eps &&
                            lon >= p.cell.lonW - eps && lon <= p.cell.lonE + eps,
                    )
                }
            }

            // no two pieces of one line overlap (band-edge rows used to come out twice)
            for ((key, pieces) in geometry.piecesByKey) {
                val spans = pieces.map { p ->
                    val v = if (key.axis == Axis.EASTING) p.latitudes else p.longitudes
                    v.min() to v.max()
                }.sortedBy { it.first }
                for (i in 1 until spans.size) {
                    assertTrue(
                        "$name: $key pieces overlap ${spans[i - 1]} / ${spans[i]}",
                        spans[i].first >= spans[i - 1].second - 1e-9,
                    )
                }
            }

            val expected = c.arr("expectedPieces").map { it.jsonObject }
            for (x in expected) {
                val key = MgrsGridRenderer.LineKey(x.i("zone"), x.south(), x.axis(), x.i("value"))
                val from = doubleArrayOf(x.arr("from").dbl(0), x.arr("from").dbl(1))
                val to = doubleArrayOf(x.arr("to").dbl(0), x.arr("to").dbl(1))
                val match = geometry.piecesByKey[key].orEmpty().firstOrNull { p ->
                    p.cell.band == x.s("band").single() &&
                        groundMetres(from, p.first()) <= x.d("fromTolMetres") &&
                        groundMetres(to, p.last()) <= x.d("toTolMetres")
                }
                assertNotNull("$name: missing piece $key band ${x.s("band")} ${from.toList()} -> ${to.toList()}", match)
                assertEquals("$name: $key level", level(x.s("level")), match!!.level)
            }

            // and nothing that isn't expected (first-column diagonals, neighbour zone lines)
            for (p in geometry.pieces) {
                if (p.lengthMetres() < 2.0) continue
                val ok = expected.any { x ->
                    x.i("zone") == p.cell.zone && x.s("band").single() == p.cell.band &&
                        x.axis() == p.axis && x.i("value") == p.value &&
                        groundMetres(doubleArrayOf(x.arr("from").dbl(0), x.arr("from").dbl(1)), p.first()) <=
                        max(x.d("fromTolMetres"), 0.5) &&
                        groundMetres(doubleArrayOf(x.arr("to").dbl(0), x.arr("to").dbl(1)), p.last()) <=
                        max(x.d("toTolMetres"), 0.5)
                }
                assertTrue("$name: unexpected piece ${p.key} in ${p.cell.gzd} ${p.first().toList()} -> ${p.last().toList()}", ok)
            }

            for (a in c.arr("absentKeys")) {
                val k = a.jsonObject
                val key = MgrsGridRenderer.LineKey(k.i("zone"), k.south(), k.axis(), k.i("value"))
                assertTrue("$name: $key must not be drawn", geometry.piecesByKey[key].isNullOrEmpty())
            }
            for (kb in c.arr("keyBounds")) {
                val k = kb.jsonObject
                val key = MgrsGridRenderer.LineKey(k.i("zone"), k.south(), k.axis(), k.i("value"))
                val pieces = geometry.piecesByKey[key].orEmpty()
                assertTrue("$name: $key should be drawn somewhere", pieces.isNotEmpty())
                for (p in pieces) {
                    assertTrue("$name: ${k.s("why")}", p.latitudes.max() <= k.d("latMax") + eps)
                }
            }
        }
    }

    // ---- labels on screen ----

    @Test
    fun labelsLandInsideTheVisibleViewport() {
        for (e in fixture.arr("visibleLabels")) {
            val c = e.jsonObject
            val name = c.s("name")
            val cam = c.obj("camera")
            val camera = MapCamera(
                centerLat = cam.d("lat"), centerLon = cam.d("lon"), zoom = cam.d("zoom"),
                headingDegrees = cam.d("headingDegrees"),
                viewportWidth = cam.d("widthDp"), viewportHeight = cam.d("heightDp"),
            )
            val lod = MgrsGridRenderer.lod(camera.zoom, camera.centerLat)
            assertEquals("$name drawn", c.levels("drawn"), lod.drawn)
            assertEquals("$name labelled", c.levels("labelled"), lod.labelled)
            val geometry = MgrsGridBuildSpec.forCamera(camera, pxPerDp = 3.0, lod = lod).build()
            val labels = MgrsGridLabels.place(geometry, camera, lod.drawn, lod.labelled, TEST_MEASURE)
            val inset = c.arr("insetRectDp")
            val expected = c.arr("labels").map { it.jsonObject }

            for (l in labels) {
                val x = expected.firstOrNull { sameLabel(it, l) }
                assertNotNull("$name: unexpected label ${describe(l)}", x)
                assertEquals("$name: text for ${describe(l)}", x!!.s("text"), l.text)
                val anchor = x.arr("anchor")
                val d = hypot(l.x - anchor.dbl(0), l.y - anchor.dbl(1))
                assertTrue(
                    "$name: ${describe(l)} at ${l.x},${l.y} is ${d}dp from ${anchor.dbl(0)},${anchor.dbl(1)}",
                    d <= x.d("anchorTolDp"),
                )
                assertTrue(
                    "$name: ${describe(l)} outside the inset viewport",
                    l.x >= inset.dbl(0) - 1e-6 && l.y >= inset.dbl(1) - 1e-6 &&
                        l.x <= inset.dbl(2) + 1e-6 && l.y <= inset.dbl(3) + 1e-6,
                )
                if (!l.isSquare) assertEquals("$name: level for ${describe(l)}", level(x.s("level")), l.level)
            }
            // declutter: nothing that got placed overlaps anything else
            for (i in labels.indices) for (j in i + 1 until labels.size) {
                val a = labels[i].box()
                val b = labels[j].box()
                assertFalse(
                    "$name: ${describe(labels[i])} overlaps ${describe(labels[j])}",
                    abs(labels[i].x - labels[j].x) < (a[0] + b[0]) / 2 + MgrsGridLabels.BOX_PAD_DP &&
                        abs(labels[i].y - labels[j].y) < (a[1] + b[1]) / 2 + MgrsGridLabels.BOX_PAD_DP,
                )
            }
            for (x in expected) {
                if (x.b("mayDrop")) continue
                assertTrue(
                    "$name: missing label ${x.s("kind")} ${x.i("zone")}${x.s("hemisphere")} ${x.s("text")}",
                    labels.any { sameLabel(x, it) },
                )
            }
        }
    }

    // ---- density, LOD bounds, cache (D4-10 / D6-06 / D4-13) ----

    @Test
    fun densityNeverChangesTheGridLevels() {
        // D6-06: Pixel-class camera, 411x914 dp at 2.625x used to get 1 km lines at 9.5 dp
        val camera = MapCamera(37.8, -122.4, 10.2, 0.0, 411.0, 914.0)
        val phone = MgrsGridBuildSpec.forCamera(camera, pxPerDp = 2.625)
        val mdpi = MgrsGridBuildSpec.forCamera(camera, pxPerDp = 1.0)
        assertEquals(listOf(GridType.HUNDRED_KILOMETER, GridType.TEN_KILOMETER), phone.drawn)
        assertEquals(mdpi.drawn, phone.drawn)
        assertEquals(mdpi.labelled, phone.labelled)
        val geometry = phone.build()
        assertTrue(geometry.pieces.isNotEmpty())
        assertTrue(geometry.pieces.none { it.level == GridType.KILOMETER })
        assertTrue("segments ${geometry.segmentCount()}", geometry.segmentCount() < 3_000)
    }

    @Test
    fun zoomedOutGridStaysSparseAndBounded() {
        // D4-13: z2 / z4 used to be 100k+ segments of 100 km mesh
        for (z in listOf(0.0, 2.0, 4.0, 5.29)) {
            val camera = MapCamera(38.0, -100.0, z, 0.0, 393.0, 852.0)
            val g = MgrsGridBuildSpec.forCamera(camera, 3.0).build()
            assertTrue("z$z draws nothing", g.pieces.isEmpty())
        }
        // z22 only shows ~20 m, so sit it on a 1 km intersection in 32U
        val ll = DoubleArray(2).also { UtmProjection.inverse(32, false, 567_000.0, 5_317_000.0, it) }
        for ((lat, z) in listOf(38.0 to 5.32, 38.0 to 9.0, 38.0 to 12.0, 60.0 to 12.0, ll[0] to 22.0)) {
            val camera = MapCamera(lat, if (z == 22.0) ll[1] else 10.0, z, 0.0, 393.0, 852.0)
            val g = MgrsGridBuildSpec.forCamera(camera, 3.0).build()
            assertTrue("z$z lat $lat has lines", g.pieces.isNotEmpty())
            assertTrue("z$z lat $lat pieces ${g.pieces.size}", g.pieces.size < 2_000)
            assertTrue("z$z lat $lat segments ${g.segmentCount()}", g.segmentCount() < 20_000)
        }
    }

    @Test
    fun cacheRebuildsOnlyWhenTheCameraLeavesTheEnvelope() {
        val camera = MapCamera(37.8, -122.45, 13.0, 0.0, 393.0, 852.0)
        val spec = MgrsGridBuildSpec.forCamera(camera, 3.0)
        fun stale(c: MapCamera, density: Double = 3.0) =
            spec.isStaleFor(c, density, MgrsGridRenderer.lod(c.zoom, c.centerLat))

        assertFalse(stale(camera))
        assertFalse(stale(camera.copy(headingDegrees = 73.0)))
        assertFalse(stale(camera.copy(zoom = 13.49)))
        assertTrue(stale(camera.copy(zoom = 13.5)))
        assertTrue(stale(camera.copy(zoom = 12.5)))
        assertFalse(stale(camera.panned(63.0, 0.0)))
        assertTrue(stale(camera.panned(64.5, 0.0)))
        assertTrue(stale(camera.panned(0.0, -64.5)))
        assertTrue(stale(camera.copy(viewportWidth = 852.0, viewportHeight = 393.0)))
        assertTrue(stale(camera, density = 2.0))
        // drawn / labelled set change rebuilds even inside the zoom band (1 km labels switch on around z12.25 here)
        val nearLabel = MapCamera(37.8, -122.45, 12.3, 0.0, 393.0, 852.0)
        val spec2 = MgrsGridBuildSpec.forCamera(nearLabel, 3.0)
        val other = nearLabel.copy(zoom = 12.2)
        assertTrue(
            MgrsGridRenderer.lod(12.2, 37.8).labelled != spec2.labelled &&
                spec2.isStaleFor(other, 3.0, MgrsGridRenderer.lod(other.zoom, other.centerLat)),
        )
    }

    @Test
    fun coverageAlwaysContainsTheViewportUntilTheNextRebuild() {
        val base = MapCamera(48.0, 12.0, 14.0, 0.0, 411.0, 914.0)
        val spec = MgrsGridBuildSpec.forCamera(base, 3.0)
        val b = spec.coverageBounds()
        for (dz in listOf(-0.499, 0.0, 0.499)) {
            for (angle in 0 until 360 step 30) {
                for (heading in 0 until 360 step 15) {
                    val r = 63.9
                    val a = Math.toRadians(angle.toDouble())
                    val cam = base.copy(zoom = base.zoom + dz, headingDegrees = heading.toDouble())
                        .panned(r * cos(a), r * kotlin.math.sin(a))
                    assertFalse(spec.isStaleFor(cam, 3.0, MgrsGridRenderer.lod(cam.zoom, cam.centerLat)))
                    for ((x, y) in listOf(0.0 to 0.0, cam.viewportWidth to 0.0, 0.0 to cam.viewportHeight,
                        cam.viewportWidth to cam.viewportHeight)) {
                        val (lat, lon) = cam.coordinate(x, y)
                        assertTrue(
                            "dz $dz heading $heading angle $angle corner $x,$y -> $lat,$lon",
                            lat >= b[0] && lat <= b[2] && lon >= b[1] && lon <= b[3],
                        )
                    }
                }
            }
        }
    }

    @Test
    fun rotatedMapStillKeepsLabelsOnScreen() {
        for (heading in listOf(30.0, 90.0, 200.0)) {
            val camera = MapCamera(37.8, -122.45, 13.0, heading, 393.0, 852.0)
            val lod = MgrsGridRenderer.lod(camera.zoom, camera.centerLat)
            val g = MgrsGridBuildSpec.forCamera(camera, 3.0, lod).build()
            val labels = MgrsGridLabels.place(g, camera, lod.drawn, lod.labelled, TEST_MEASURE)
            assertTrue("heading $heading has labels", labels.count { !it.isSquare } >= 10)
            for (l in labels) {
                assertTrue(
                    "heading $heading ${describe(l)} at ${l.x},${l.y}",
                    l.x in 15.999..377.001 && l.y in 15.999..836.001,
                )
            }
        }
    }

    // ---- helpers ----

    private fun sameLabel(x: JsonObject, l: MgrsGridLabels.Label): Boolean {
        if ((x.s("kind") == "square") != l.isSquare) return false
        if (x.i("zone") != l.zone || x.south() != l.south) return false
        return if (l.isSquare) {
            x.s("band").single() == l.band && x.i("easting") == l.easting && x.i("northing") == l.northing
        } else {
            x.axis() == l.axis && x.i("value") == l.value
        }
    }

    private fun describe(l: MgrsGridLabels.Label): String =
        if (l.isSquare) "square ${l.zone}${l.band} ${l.easting}/${l.northing} ${l.text}"
        else "line ${l.zone}${if (l.south) "S" else "N"} ${l.axis} ${l.value} ${l.text}"

    private fun MgrsGridLabels.Label.box(): DoubleArray {
        val m = TEST_MEASURE.measure(text, level)
        return if (rotated) doubleArrayOf(m[1], m[0]) else m
    }

    private fun MgrsGridRenderer.Piece.first() = doubleArrayOf(latitudes.first(), longitudes.first())
    private fun MgrsGridRenderer.Piece.last() = doubleArrayOf(latitudes.last(), longitudes.last())
    private fun MgrsGridRenderer.Piece.lengthMetres(): Double =
        (1 until latitudes.size).sumOf {
            groundMetres(doubleArrayOf(latitudes[it - 1], longitudes[it - 1]), doubleArrayOf(latitudes[it], longitudes[it]))
        }

    private fun MgrsGridRenderer.GridGeometry.segmentCount(): Int = pieces.sumOf { it.latitudes.size - 1 }

    private fun MapCamera.panned(dxDp: Double, dyDp: Double): MapCamera {
        val w = WebMercator.worldPoint(centerLat, centerLon, zoom)
        val (lat, lon) = WebMercator.coordinate(com.tacmap.map.render.Vec2(w.x + dxDp, w.y + dyDp), zoom)
        return copy(centerLat = lat, centerLon = lon)
    }

    companion object {
        private const val R = 6_378_137.0

        // rough bold-digit metrics in dp at font scale 1, smaller than the fixture's generous boxes
        val TEST_MEASURE = MgrsGridLabels.TextMeasure { text, level ->
            val sp = MgrsGridRenderer.labelTextSp(level).toDouble()
            doubleArrayOf(0.6 * sp * text.length + 1.0, 1.2 * sp)
        }

        private fun merc(p: DoubleArray): DoubleArray {
            val lat = p[0].coerceIn(-WebMercator.LAT_LIMIT, WebMercator.LAT_LIMIT)
            return doubleArrayOf(R * Math.toRadians(p[1]), R * ln(tan(PI / 4 + Math.toRadians(lat) / 2)))
        }

        /** point to polyline, web mercator metres times cos(point lat), as the README says */
        fun polylineDistance(p: DoubleArray, poly: List<DoubleArray>): Double {
            val q = merc(p)
            var best = Double.MAX_VALUE
            var prev = merc(poly[0])
            if (poly.size == 1) best = hypot(q[0] - prev[0], q[1] - prev[1])
            for (i in 1 until poly.size) {
                val cur = merc(poly[i])
                best = min(best, MgrsGridRenderer.segDist(q[0], q[1], prev[0], prev[1], cur[0], cur[1]))
                prev = cur
            }
            return best * cos(Math.toRadians(p[0]))
        }

        fun groundMetres(a: DoubleArray, b: DoubleArray): Double {
            val lat = Math.toRadians((a[0] + b[0]) / 2)
            val dy = Math.toRadians(b[0] - a[0]) * R
            val dx = Math.toRadians(b[1] - a[1]) * R * cos(lat)
            return hypot(dx, dy)
        }
    }
}
