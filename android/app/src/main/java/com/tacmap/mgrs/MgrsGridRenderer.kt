package com.tacmap.mgrs

import com.tacmap.map.render.WebMercator
import mil.nga.mgrs.grid.GridType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * MGRS grid geometry for the map overlay. Lines are built per GZD cell in that
 * zone's own UTM (accurate Kruger TM, not the NGA lib), densified so the chords
 * stay within 0.25 px of the real curve, and clipped to the cell's lon/lat span.
 * Level of detail is picked in dp so every screen density gets the same grid.
 *
 * The whole policy (levels, LOD thresholds, label text, clipping, label layout)
 * is pinned by testdata/mgrs_grid.json and shared with iOS MGRSGridRenderer.
 * Label layout lives in MgrsGridLabels, the rebuild cache in MgrsGridBuildSpec.
 */
object MgrsGridRenderer {

    /** Tactical-mode neutral dark-grey for grid lines AND labels. */
    val INK_COLOR: Int = 0xD9303030.toInt()
    val LABEL_TEXT_COLOR: Int = 0xFF282828.toInt()

    // ---- policy, keep in sync with testdata/mgrs_grid.json "policy" ----

    /** coarse -> fine. no 100 m level and no GZD line level */
    val LEVELS: List<GridType> = listOf(
        GridType.HUNDRED_KILOMETER,
        GridType.TEN_KILOMETER,
        GridType.KILOMETER,
    )
    const val TILE_SIZE_DP = 256.0
    const val EARTH_RADIUS_METRES = 6_378_137.0
    const val LINE_MIN_DP = 32.0
    const val LABEL_MIN_DP = 40.0
    const val LABEL_INSET_DP = 16.0
    const val SQUARE_LABEL_MIN_DP = 40.0
    const val SQUARE_LABEL_OFFSET_DP = 32.0
    const val MAX_SAGITTA_PX = 0.25
    const val GRID_LAT_MIN = -80.0
    const val GRID_LAT_MAX = 84.0
    const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"
    const val EVEN_ZONE_ROW_SHIFT = 5
    // indexed by zone % 3
    private val COLUMN_SETS = arrayOf("STUVWXYZ", "ABCDEFGH", "JKLMNPQR")

    // cells whose lon span isn't the plain 6 deg strip. null = cell doesn't exist
    private val SPAN_EXCEPTIONS: Map<String, DoubleArray?> = mapOf(
        "31V" to doubleArrayOf(0.0, 3.0), "32V" to doubleArrayOf(3.0, 12.0),
        "31X" to doubleArrayOf(0.0, 9.0), "32X" to null, "33X" to doubleArrayOf(9.0, 21.0),
        "34X" to null, "35X" to doubleArrayOf(21.0, 33.0), "36X" to null,
        "37X" to doubleArrayOf(33.0, 42.0),
    )

    fun metres(level: GridType): Int = level.precision

    fun lineWidthDp(type: GridType): Float = when (type) {
        GridType.HUNDRED_KILOMETER -> 2.0f
        GridType.TEN_KILOMETER     -> 1.3f
        GridType.KILOMETER         -> 0.8f
        else                       -> 0.6f
    }

    /** Existing density-scaled width plus exactly one physical display pixel. */
    fun lineWidthPx(type: GridType, density: Float): Float =
        lineWidthDp(type) * density + 1f

    fun labelTextSp(type: GridType): Float = when (type) {
        GridType.HUNDRED_KILOMETER -> 14f
        GridType.TEN_KILOMETER     -> 12f
        GridType.KILOMETER         -> 11f
        else                       -> 9f
    }

    // ---- level of detail ----

    class Lod(
        val spacingDp: Map<GridType, Double>,
        /** levels whose lines get drawn, coarse -> fine */
        val drawn: List<GridType>,
        /** levels whose labels get drawn, coarse -> fine */
        val labelled: List<GridType>,
    )

    fun metresPerDp(latitude: Double, zoom: Double): Double {
        val lat = latitude.coerceIn(-WebMercator.LAT_LIMIT, WebMercator.LAT_LIMIT)
        return cos(Math.toRadians(lat)) * 2.0 * PI * EARTH_RADIUS_METRES / (TILE_SIZE_DP * 2.0.pow(zoom))
    }

    /** zoom is the continuous camera zoom (256 dp tiles), lat the camera-centre latitude. */
    fun lod(zoom: Double, latitude: Double): Lod {
        val mpd = metresPerDp(latitude, zoom)
        val spacing = LEVELS.associateWith { metres(it) / mpd }
        return Lod(
            spacingDp = spacing,
            drawn = LEVELS.filter { spacing.getValue(it) >= LINE_MIN_DP },
            labelled = LEVELS.filter { spacing.getValue(it) >= LABEL_MIN_DP },
        )
    }

    /** Coarsest drawn level whose spacing divides the value, so N=4200000 is always a 100 km line. */
    fun ownerLevel(value: Int, drawn: Collection<GridType>): GridType? =
        LEVELS.firstOrNull { it in drawn && value % metres(it) == 0 }

    /**
     * Line label = the line's own value, %02d of the km within its 100 km square.
     * Only lines on the finest labelled level (10 km or 1 km) get one; null when
     * only 100 km is labelled (the squares carry letters then).
     */
    fun lineLabelText(value: Int, labelled: Collection<GridType>): String? {
        val step = when {
            GridType.KILOMETER in labelled -> 1_000
            GridType.TEN_KILOMETER in labelled -> 10_000
            else -> return null
        }
        if (value % step != 0) return null
        val km = (value % 100_000) / 1_000
        return if (km < 10) "0$km" else km.toString()
    }

    /** 100 km square letters (AA scheme) for the square whose SW corner is easting/northing. */
    fun squareLetters(zone: Int, easting: Int, northing: Int): String {
        val column = COLUMN_SETS[zone % 3][easting / 100_000 - 1]
        var row = (northing / 100_000) % 20
        if (zone % 2 == 0) row = (row + EVEN_ZONE_ROW_SHIFT) % 20
        return "$column${ROW_LETTERS[row]}"
    }

    // ---- GZD cells ----

    /** One grid zone designation cell: lon span [lonW, lonE), lat span [latS, latN). */
    data class Cell(
        val zone: Int,
        val band: Char,
        val lonW: Double,
        val lonE: Double,
        val latS: Double,
        val latN: Double,
    ) {
        val south: Boolean get() = band < 'N'
        val gzd: String get() = "$zone$band"
    }

    fun cell(zone: Int, band: Char): Cell? {
        val i = BANDS.indexOf(band)
        if (i < 0 || zone !in 1..60) return null
        val latS = -80.0 + 8.0 * i
        val latN = if (band == 'X') 84.0 else latS + 8.0
        val key = "$zone$band"
        val span = if (SPAN_EXCEPTIONS.containsKey(key)) {
            SPAN_EXCEPTIONS[key] ?: return null
        } else {
            doubleArrayOf(6.0 * zone - 186.0, 6.0 * zone - 180.0)
        }
        return Cell(zone, band, span[0], span[1], latS, latN)
    }

    /** Every cell overlapping the box, band by band, zone by zone. */
    fun cellsIn(south: Double, west: Double, north: Double, east: Double): List<Cell> {
        val out = ArrayList<Cell>()
        for ((i, b) in BANDS.withIndex()) {
            val latS = -80.0 + 8.0 * i
            val latN = if (b == 'X') 84.0 else latS + 8.0
            if (!(latS < north && latN > south)) continue
            for (z in 1..60) {
                val c = cell(z, b) ?: continue
                if (c.lonW < east && c.lonE > west) out += c
            }
        }
        return out
    }

    // ---- built geometry ----

    enum class Axis { EASTING, NORTHING }

    data class LineKey(val zone: Int, val south: Boolean, val axis: Axis, val value: Int)

    /**
     * One clipped run of a grid line inside one GZD cell. Vertices run in
     * increasing northing (eastings) or easting (northings). Merc arrays are Web
     * Mercator in 0..1 world units so a frame can project without any trig.
     */
    class Piece internal constructor(
        val cell: Cell,
        val axis: Axis,
        val value: Int,
        /** owner level, sets stroke + font */
        val level: GridType,
        val latitudes: DoubleArray,
        val longitudes: DoubleArray,
        internal val mercX: DoubleArray,
        internal val mercY: DoubleArray,
    ) {
        val key: LineKey get() = LineKey(cell.zone, cell.south, axis, value)
        internal val minX = mercX.min()
        internal val maxX = mercX.max()
        internal val minY = mercY.min()
        internal val maxY = mercY.max()
    }

    /** A 100 km square outline (SW corner easting/northing) in its own cell, for the letters. */
    class SquareRing internal constructor(
        val cell: Cell,
        val easting: Int,
        val northing: Int,
        val text: String,
        internal val mercX: DoubleArray,
        internal val mercY: DoubleArray,
    ) {
        internal val minX = mercX.min()
        internal val maxX = mercX.max()
        internal val minY = mercY.min()
        internal val maxY = mercY.max()
    }

    class GridGeometry(
        val drawn: List<GridType>,
        val pieces: List<Piece>,
        val squares: List<SquareRing>,
    ) {
        val piecesByKey: Map<LineKey, List<Piece>> by lazy { pieces.groupBy { it.key } }

        companion object {
            val EMPTY = GridGeometry(emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Build every drawn line (and optionally the 100 km squares) inside the box.
     * Pieces are clipped to cell AND box, cell north/east edges half open so
     * nothing on a shared edge is emitted twice. [densifyZoom] + [pxPerDp] set the
     * 0.25 px sagitta budget. [checkCancelled] gets poked between lines so a
     * background build can bail out when the camera has already moved on.
     */
    fun build(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        drawn: Collection<GridType>,
        withSquares: Boolean,
        densifyZoom: Double,
        pxPerDp: Double,
        checkCancelled: () -> Unit = {},
    ): GridGeometry {
        val levels = LEVELS.filter { it in drawn }
        if (levels.isEmpty()) return GridGeometry.EMPTY
        if (!(south < north && west < east) || !south.isFinite() || !north.isFinite() ||
            !west.isFinite() || !east.isFinite() || !densifyZoom.isFinite() || !(pxPerDp > 0.0)
        ) return GridGeometry.EMPTY

        val pxScale = TILE_SIZE_DP * 2.0.pow(densifyZoom) * pxPerDp
        val regions = cellsIn(south, west, north, east)
            .map { Region.of(it, south, west, north, east) }
            .filterNot { it.isEmpty }
        val pieces = ArrayList<Piece>()
        val squares = ArrayList<SquareRing>()

        // densify each line once per zone + hemisphere, then split at every cell
        // edge in that group so neighbouring pieces meet at exactly the same point
        for ((group, groupRegions) in regions.groupBy { it.cell.zone to it.cell.south }) {
            val (zone, isSouth) = group
            val ext = DoubleArray(4) { if (it % 2 == 0) Double.MAX_VALUE else -Double.MAX_VALUE }
            groupRegions.forEach { it.utmExtent(ext) }
            val latEdges = groupRegions.flatMap { listOf(it.latS, it.latN) }.distinct().toDoubleArray()
            val lonEdges = groupRegions.flatMap { listOf(it.lonW, it.lonE) }.distinct().toDoubleArray()

            for (axis in Axis.values()) {
                val lo = if (axis == Axis.EASTING) ext[0] else ext[2]
                val hi = if (axis == Axis.EASTING) ext[1] else ext[3]
                val tLo = (if (axis == Axis.EASTING) ext[2] else ext[0]) - EXTENT_PAD_M
                val tHi = (if (axis == Axis.EASTING) ext[3] else ext[1]) + EXTENT_PAD_M
                val values = sortedSetOf<Int>()
                for (level in levels) {
                    val m = metres(level)
                    var v = ceil((lo - EXTENT_PAD_M) / m).toLong() * m
                    val last = floor((hi + EXTENT_PAD_M) / m).toLong() * m
                    while (v <= last) { values += v.toInt(); v += m }
                }
                for (value in values) {
                    // the equator belongs to the northern hemisphere (N=0) only
                    if (axis == Axis.NORTHING && isSouth && value == 10_000_000) continue
                    val owner = ownerLevel(value, levels) ?: continue
                    checkCancelled()
                    val fn = LineFn(zone, isSouth, axis, value.toDouble())
                    val breaks = ArrayList<Double>(1)
                    // monotonic halves: a northing line peaks at the CM, an easting
                    // line's lon turns at the equator. keeps edge crossings single
                    val special = if (axis == Axis.NORTHING) UtmProjection.FALSE_EASTING
                    else if (isSouth) UtmProjection.FALSE_NORTHING_SOUTH else 0.0
                    if (special > tLo && special < tHi) breaks += special
                    val line = densify(fn, tLo, tHi, breaks, pxScale)
                    insertCrossings(line, fn, latEdges, lonEdges)
                    for (region in groupRegions) {
                        split(line, region) { lats, lons, mx, my ->
                            pieces += Piece(region.cell, axis, value, owner, lats, lons, mx, my)
                        }
                    }
                }
            }
        }

        if (withSquares && GridType.HUNDRED_KILOMETER in levels) {
            // a square whose diagonal is under 40 dp even at the most zoomed in camera this
            // build serves can't pass the square label size check at any heading, so don't
            // build it (0.9 because the corner box ignores the edge bulge). same rule as iOS.
            // without it z3 near 83.5N built ~67k squares
            val minDiagonal = 0.9 * SQUARE_LABEL_MIN_DP / (TILE_SIZE_DP * 2.0.pow(densifyZoom))
            val corner = DoubleArray(2)
            for (region in regions) {
                checkCancelled()
                val ext = DoubleArray(4) { if (it % 2 == 0) Double.MAX_VALUE else -Double.MAX_VALUE }
                region.utmExtent(ext)
                val c = region.cell
                var e0 = floor(ext[0] / 100_000.0).toInt() * 100_000
                while (e0 <= ext[1]) {
                    if (e0 in 100_000 until 900_000) {
                        var n0 = floor(ext[2] / 100_000.0).toInt() * 100_000
                        while (n0 <= ext[3]) {
                            if (!(c.south && n0 >= 10_000_000) &&
                                squareDiagonal(c.zone, c.south, e0, n0, corner) >= minDiagonal
                            ) {
                                squares += squareRing(c, e0, n0, pxScale)
                            }
                            n0 += 100_000
                        }
                    }
                    e0 += 100_000
                }
            }
        }
        // group the label keys here, off the main thread, not on the first frame
        return GridGeometry(levels, pieces, squares).also { it.piecesByKey }
    }

    // ---- internals ----

    private const val EXTENT_PAD_M = 5.0
    private const val INITIAL_STEP_M = 250_000.0
    private const val MIN_STEP_M = 1e-3
    private const val MAX_DEPTH = 40
    private const val EDGE_EPS = 1e-12

    internal fun mercX(longitude: Double): Double = (longitude + 180.0) / 360.0

    internal fun mercY(latitude: Double): Double {
        val lat = latitude.coerceIn(-WebMercator.LAT_LIMIT, WebMercator.LAT_LIMIT)
        val s = sin(Math.toRadians(lat))
        return 0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)
    }

    /** Cell clipped to the box (and -80..84). Box edges closed, cell N/E edges half open. */
    internal class Region(
        val cell: Cell,
        val latS: Double,
        val latN: Double,
        val lonW: Double,
        val lonE: Double,
        val northOpen: Boolean,
        val eastOpen: Boolean,
    ) {
        val isEmpty: Boolean get() = latS >= latN || lonW >= lonE

        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < latS - EDGE_EPS || lon < lonW - EDGE_EPS) return false
            if (northOpen) { if (lat >= latN - EDGE_EPS) return false } else if (lat > latN + EDGE_EPS) return false
            if (eastOpen) { if (lon >= lonE - EDGE_EPS) return false } else if (lon > lonE + EDGE_EPS) return false
            return true
        }

        /** Grow ext = [eMin, eMax, nMin, nMax] by this region's UTM footprint. */
        fun utmExtent(ext: DoubleArray) {
            // extremes sit on the corners, or on the N/S edge at the CM for northings
            val out = DoubleArray(2)
            val cm = UtmProjection.centralMeridian(cell.zone)
            val k = 8
            fun take(lat: Double, lon: Double) {
                UtmProjection.forward(cell.zone, cell.south, lat, lon, out)
                ext[0] = min(ext[0], out[0]); ext[1] = max(ext[1], out[0])
                ext[2] = min(ext[2], out[1]); ext[3] = max(ext[3], out[1])
            }
            for (i in 0..k) {
                val f = i.toDouble() / k
                val lon = lonW + (lonE - lonW) * f
                val lat = latS + (latN - latS) * f
                take(latS, lon); take(latN, lon); take(lat, lonW); take(lat, lonE)
            }
            if (cm > lonW && cm < lonE) { take(latS, cm); take(latN, cm) }
        }

        companion object {
            fun of(cell: Cell, south: Double, west: Double, north: Double, east: Double) = Region(
                cell = cell,
                latS = maxOf(cell.latS, south, GRID_LAT_MIN),
                latN = minOf(cell.latN, north, GRID_LAT_MAX),
                lonW = max(cell.lonW, west),
                lonE = min(cell.lonE, east),
                northOpen = cell.latN <= north,
                eastOpen = cell.lonE <= east,
            )
        }
    }

    /** A grid line as a function of its free coordinate t (northing for eastings, easting for northings). */
    private class LineFn(val zone: Int, val south: Boolean, val axis: Axis, val value: Double) {
        private val out = DoubleArray(2)
        var lat = 0.0
        var lon = 0.0

        fun eval(t: Double) {
            if (axis == Axis.EASTING) UtmProjection.inverse(zone, south, value, t, out)
            else UtmProjection.inverse(zone, south, t, value, out)
            lat = out[0]
            lon = out[1]
        }
    }

    /** Growable vertex list, t + lat/lon + merc. */
    private class Poly(capacity: Int = 32) {
        var size = 0
        var t = DoubleArray(capacity)
        var lat = DoubleArray(capacity)
        var lon = DoubleArray(capacity)
        var mx = DoubleArray(capacity)
        var my = DoubleArray(capacity)

        fun add(tv: Double, la: Double, lo: Double, x: Double = mercX(lo), y: Double = mercY(la)) {
            if (size == t.size) {
                val n = size * 2
                t = t.copyOf(n); lat = lat.copyOf(n); lon = lon.copyOf(n); mx = mx.copyOf(n); my = my.copyOf(n)
            }
            t[size] = tv; lat[size] = la; lon[size] = lo; mx[size] = x; my[size] = y
            size++
        }
    }

    /**
     * Adaptive densify from t0 to t1 (either direction): split any chord whose
     * true midpoint sits more than 0.25 px off it at pxScale (merc unit -> px).
     * [breaks] are forced vertices.
     */
    private fun densify(
        fn: LineFn,
        t0: Double,
        t1: Double,
        breaks: List<Double>,
        pxScale: Double,
        minSegments: Int = 2,
        poly: Poly = Poly(),
    ): Poly {
        val knots = ArrayList<Double>()
        val span = t1 - t0
        val n0 = max(minSegments, ceil(abs(span) / INITIAL_STEP_M).toInt())
        for (i in 0..n0) knots += if (i == n0) t1 else t0 + span * i / n0
        knots += breaks
        knots.sort()
        if (span < 0) knots.reverse()

        fn.eval(knots[0])
        var ta = knots[0]
        var xa = mercX(fn.lon)
        var ya = mercY(fn.lat)
        poly.add(ta, fn.lat, fn.lon, xa, ya)
        for (i in 1 until knots.size) {
            val tb = knots[i]
            if (tb == ta) continue
            fn.eval(tb)
            val lb = fn.lat; val loB = fn.lon
            val xb = mercX(loB); val yb = mercY(lb)
            refine(fn, poly, ta, xa, ya, tb, lb, loB, xb, yb, pxScale, 0)
            ta = tb; xa = xb; ya = yb
        }
        return poly
    }

    private fun refine(
        fn: LineFn, poly: Poly,
        ta: Double, xa: Double, ya: Double,
        tb: Double, latB: Double, lonB: Double, xb: Double, yb: Double,
        pxScale: Double, depth: Int,
    ) {
        if (depth < MAX_DEPTH && abs(tb - ta) > MIN_STEP_M) {
            val tm = 0.5 * (ta + tb)
            fn.eval(tm)
            val lm = fn.lat; val lom = fn.lon
            val xm = mercX(lom); val ym = mercY(lm)
            if (segDist(xm, ym, xa, ya, xb, yb) * pxScale > MAX_SAGITTA_PX) {
                refine(fn, poly, ta, xa, ya, tm, lm, lom, xm, ym, pxScale, depth + 1)
                refine(fn, poly, tm, xm, ym, tb, latB, lonB, xb, yb, pxScale, depth + 1)
                return
            }
        }
        poly.add(tb, latB, lonB, xb, yb)
    }

    internal fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * Put an exact vertex (on the true curve, snapped onto the edge) wherever the
     * line crosses a cell/box edge, so the clip is exact rather than a chord cut.
     */
    private fun insertCrossings(line: Poly, fn: LineFn, latEdges: DoubleArray, lonEdges: DoubleArray) {
        val hits = ArrayList<DoubleArray>()  // [t, lat, lon]
        val out = Poly(line.size + 8)
        out.add(line.t[0], line.lat[0], line.lon[0], line.mx[0], line.my[0])
        for (i in 0 until line.size - 1) {
            hits.clear()
            for (edge in latEdges) crossing(fn, line, i, edge, isLat = true)?.let { hits += it }
            for (edge in lonEdges) crossing(fn, line, i, edge, isLat = false)?.let { hits += it }
            if (hits.size > 1) {
                if (line.t[i + 1] > line.t[i]) hits.sortBy { it[0] } else hits.sortByDescending { it[0] }
            }
            for (h in hits) out.add(h[0], h[1], h[2])
            out.add(line.t[i + 1], line.lat[i + 1], line.lon[i + 1], line.mx[i + 1], line.my[i + 1])
        }
        line.size = 0
        for (i in 0 until out.size) line.add(out.t[i], out.lat[i], out.lon[i], out.mx[i], out.my[i])
    }

    private fun crossing(fn: LineFn, line: Poly, i: Int, edge: Double, isLat: Boolean): DoubleArray? {
        val g0 = (if (isLat) line.lat[i] else line.lon[i]) - edge
        val g1 = (if (isLat) line.lat[i + 1] else line.lon[i + 1]) - edge
        if (!(g0 < 0.0 && g1 > 0.0 || g0 > 0.0 && g1 < 0.0)) return null
        // bracketed regula falsi (Illinois), the curve is smooth + monotonic here
        // so it lands in a handful of evals instead of ~40 bisection steps
        var a = line.t[i]
        var b = line.t[i + 1]
        var ga = g0
        var gb = g1
        var side = 0
        var t = a
        for (iter in 0 until 60) {
            t = (a * gb - b * ga) / (gb - ga)
            if (!(t > min(a, b) && t < max(a, b))) t = 0.5 * (a + b)
            fn.eval(t)
            val gt = (if (isLat) fn.lat else fn.lon) - edge
            if (gt == 0.0 || abs(b - a) < 1e-6) break
            if ((gt < 0.0) == (ga < 0.0)) {
                a = t; ga = gt
                if (side == -1) gb *= 0.5
                side = -1
            } else {
                b = t; gb = gt
                if (side == 1) ga *= 0.5
                side = 1
            }
            if (abs(gt) < 1e-13) break
        }
        // fn still holds the point at t. snap onto the edge so both neighbours share the exact same point
        return if (isLat) doubleArrayOf(t, edge, fn.lon) else doubleArrayOf(t, fn.lat, edge)
    }

    /** Walk the line and emit each maximal run inside the region. */
    private fun split(
        line: Poly,
        region: Region,
        emit: (DoubleArray, DoubleArray, DoubleArray, DoubleArray) -> Unit,
    ) {
        var start = -1
        var end = -1
        fun flush() {
            if (start >= 0 && end > start) {
                val n = end - start + 1
                emit(
                    line.lat.copyOfRange(start, start + n), line.lon.copyOfRange(start, start + n),
                    line.mx.copyOfRange(start, start + n), line.my.copyOfRange(start, start + n),
                )
            }
            start = -1
            end = -1
        }
        for (i in 0 until line.size - 1) {
            if (line.t[i + 1] == line.t[i]) continue
            val inside = region.contains(
                0.5 * (line.lat[i] + line.lat[i + 1]),
                0.5 * (line.lon[i] + line.lon[i + 1]),
            )
            if (inside) {
                if (start < 0) start = i
                end = i + 1
            } else if (start >= 0) {
                flush()
            }
        }
        flush()
    }

    /** merc (0..1) bbox diagonal of a 100 km square's four corners */
    private fun squareDiagonal(zone: Int, south: Boolean, e0: Int, n0: Int, out: DoubleArray): Double {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (k in 0 until 4) {
            val e = e0 + if (k == 1 || k == 2) 100_000.0 else 0.0
            val n = n0 + if (k >= 2) 100_000.0 else 0.0
            UtmProjection.inverse(zone, south, e, n, out)
            val x = mercX(out[1])
            val y = mercY(out[0])
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
        return hypot(maxX - minX, maxY - minY)
    }

    private fun squareRing(cell: Cell, e0: Int, n0: Int, pxScale: Double): SquareRing {
        val e1 = e0 + 100_000.0
        val n1 = n0 + 100_000.0
        val z = cell.zone
        val s = cell.south
        val edges = listOf(
            Triple(LineFn(z, s, Axis.NORTHING, n0.toDouble()), e0.toDouble(), e1),
            Triple(LineFn(z, s, Axis.EASTING, e1), n0.toDouble(), n1),
            Triple(LineFn(z, s, Axis.NORTHING, n1), e1, e0.toDouble()),
            Triple(LineFn(z, s, Axis.EASTING, e0.toDouble()), n1, n0.toDouble()),
        )
        val ring = Poly(16)
        for ((fn, a, b) in edges) {
            // each edge starts where the last one ended, drop the duplicate corner
            if (ring.size > 0) ring.size--
            densify(fn, a, b, emptyList(), pxScale, minSegments = 1, poly = ring)
        }
        ring.size--  // last corner == first corner
        return SquareRing(
            cell, e0, n0, squareLetters(z, e0, n0),
            ring.mx.copyOf(ring.size), ring.my.copyOf(ring.size),
        )
    }
}
