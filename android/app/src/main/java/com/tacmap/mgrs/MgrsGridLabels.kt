package com.tacmap.mgrs

import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.WebMercator
import com.tacmap.mgrs.MgrsGridRenderer.Axis
import com.tacmap.mgrs.MgrsGridRenderer.GridGeometry
import com.tacmap.mgrs.MgrsGridRenderer.LABEL_INSET_DP
import com.tacmap.mgrs.MgrsGridRenderer.SQUARE_LABEL_MIN_DP
import com.tacmap.mgrs.MgrsGridRenderer.SQUARE_LABEL_OFFSET_DP
import mil.nga.mgrs.grid.GridType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grid label layout, redone in screen space every frame against the visible
 * viewport (inset 16 dp), never the oversized coverage square the geometry is
 * built for. That's what kept the old labels parked off screen.
 *
 * Rules are pinned by testdata/mgrs_grid.json (visibleLabels) and shared with
 * iOS. All positions are dp with the origin top-left, same as MapCamera.
 */
object MgrsGridLabels {

    /** Pad between placed label boxes. */
    const val BOX_PAD_DP = 2.0

    /** Lines with less than this showing inside the label region get no label (same as iOS). */
    const val MIN_LABELLED_LENGTH_DP = 1.0

    class Label(
        val isSquare: Boolean,
        val zone: Int,
        val south: Boolean,
        /** band of the cell the label was placed in */
        val band: Char,
        /** line labels only */
        val axis: Axis?,
        /** line value, or 0 for a square */
        val value: Int,
        /** square SW corner, 0 for a line */
        val easting: Int,
        val northing: Int,
        val level: GridType,
        val text: String,
        /** text centre, dp */
        val x: Double,
        val y: Double,
        /** easting labels are drawn turned -90 deg */
        val rotated: Boolean,
    )

    /** Unrotated text box in dp for a label on this level. */
    fun interface TextMeasure {
        fun measure(text: String, level: GridType): DoubleArray
    }

    fun place(
        geometry: GridGeometry,
        camera: MapCamera,
        drawn: Collection<GridType>,
        labelled: Collection<GridType>,
        measure: TextMeasure,
    ): List<Label> {
        if (labelled.isEmpty() || camera.viewportWidth <= 2 * LABEL_INSET_DP ||
            camera.viewportHeight <= 2 * LABEL_INSET_DP
        ) return emptyList()
        val frame = GridScreenFrame(camera)
        val inset = frame.insetPlanes(LABEL_INSET_DP)

        val squares = ArrayList<Candidate>()
        if (GridType.HUNDRED_KILOMETER in labelled) {
            placeSquares(geometry, frame, inset, squares)
            squares.sortWith(compareBy({ it.label.y }, { it.label.x }))
        }
        val lines = ArrayList<Candidate>()
        placeLines(geometry, frame, inset, drawn, labelled, lines)
        val levelIndex = MgrsGridRenderer.LEVELS
        lines.sortWith(
            compareBy<Candidate>(
                { levelIndex.indexOf(it.label.level) },
                { if (it.label.axis == Axis.EASTING) 0 else 1 },
                { if (it.label.axis == Axis.EASTING) it.worldX else it.worldY },
                { it.label.zone },
                { it.label.value },
            ),
        )

        // squares first, then lines coarse -> fine; drop anything that'd overlap
        val placed = ArrayList<Label>()
        val boxes = ArrayList<DoubleArray>()
        for (c in squares + lines) {
            val l = c.label
            val m = measure.measure(l.text, l.level)
            val w = if (l.rotated) m[1] else m[0]
            val h = if (l.rotated) m[0] else m[1]
            val hit = boxes.any { b ->
                abs(l.x - b[0]) < (w + b[2]) / 2 + BOX_PAD_DP && abs(l.y - b[1]) < (h + b[3]) / 2 + BOX_PAD_DP
            }
            if (hit) continue
            boxes += doubleArrayOf(l.x, l.y, w, h)
            placed += l
        }
        return placed
    }

    private class Candidate(val label: Label, val worldX: Double, val worldY: Double)

    /**
     * One label per (zone, hemisphere, axis, value): eastings at the north end of
     * the line's visible part, northings at the west end. Visible = inside the
     * inset viewport and the line's own zone span pulled in 16 dp each side.
     */
    private fun placeLines(
        geometry: GridGeometry,
        frame: GridScreenFrame,
        inset: List<DoubleArray>,
        drawn: Collection<GridType>,
        labelled: Collection<GridType>,
        out: MutableList<Candidate>,
    ) {
        val step = when {
            GridType.KILOMETER in labelled -> 1_000
            GridType.TEN_KILOMETER in labelled -> 10_000
            else -> return
        }
        val planes = ArrayList<DoubleArray>(6)
        for ((key, pieces) in geometry.piecesByKey) {
            if (key.value % step != 0) continue
            val text = MgrsGridRenderer.lineLabelText(key.value, labelled) ?: continue
            var bestScore = Double.MAX_VALUE
            var bestX = 0.0
            var bestY = 0.0
            var bestPiece: MgrsGridRenderer.Piece? = null
            var visibleLen = 0.0
            for (piece in pieces) {
                if (piece.level !in drawn) continue
                if (!frame.mayBeVisible(piece.minX, piece.minY, piece.maxX, piece.maxY)) continue
                planes.clear()
                planes.addAll(inset)
                planes += frame.worldXAtLeast(MgrsGridRenderer.mercX(piece.cell.lonW) * frame.size + LABEL_INSET_DP)
                planes += frame.worldXAtMost(MgrsGridRenderer.mercX(piece.cell.lonE) * frame.size - LABEL_INSET_DP)
                var x0 = frame.sx(piece.mercX[0], piece.mercY[0])
                var y0 = frame.sy(piece.mercX[0], piece.mercY[0])
                for (i in 1 until piece.mercX.size) {
                    val x1 = frame.sx(piece.mercX[i], piece.mercY[i])
                    val y1 = frame.sy(piece.mercX[i], piece.mercY[i])
                    val span = clipSegment(x0, y0, x1, y1, planes)
                    if (span != null) {
                        visibleLen += (span[1] - span[0]) * hypot(x1 - x0, y1 - y0)
                        for (t in span) {
                            val px = x0 + (x1 - x0) * t
                            val py = y0 + (y1 - y0) * t
                            val score = if (key.axis == Axis.EASTING) frame.worldY(px, py) else frame.worldX(px, py)
                            if (score < bestScore) {
                                bestScore = score; bestX = px; bestY = py; bestPiece = piece
                            }
                        }
                    }
                    x0 = x1; y0 = y1
                }
            }
            val p = bestPiece ?: continue
            // a line that only nicks the corner of the view doesn't earn a label
            if (visibleLen < MIN_LABELLED_LENGTH_DP) continue
            val label = Label(
                isSquare = false, zone = key.zone, south = key.south, band = p.cell.band,
                axis = key.axis, value = key.value, easting = 0, northing = 0,
                level = p.level, text = text, x = bestX, y = bestY,
                rotated = key.axis == Axis.EASTING,
            )
            out += Candidate(label, frame.worldX(bestX, bestY), frame.worldY(bestX, bestY))
        }
    }

    /**
     * P = the 100 km square clipped to its cell's label region. Skip if P's box is
     * under 40 dp either way, else anchor 32 dp in from P's top-left-most vertex,
     * or step toward P's centroid when that lands outside P.
     */
    private fun placeSquares(
        geometry: GridGeometry,
        frame: GridScreenFrame,
        inset: List<DoubleArray>,
        out: MutableList<Candidate>,
    ) {
        val planes = ArrayList<DoubleArray>(8)
        for (ring in geometry.squares) {
            if (!frame.mayBeVisible(ring.minX, ring.minY, ring.maxX, ring.maxY)) continue
            val c = ring.cell
            planes.clear()
            planes.addAll(inset)
            planes += frame.worldXAtLeast(MgrsGridRenderer.mercX(c.lonW) * frame.size + LABEL_INSET_DP)
            planes += frame.worldXAtMost(MgrsGridRenderer.mercX(c.lonE) * frame.size - LABEL_INSET_DP)
            planes += frame.worldYAtLeast(MgrsGridRenderer.mercY(c.latN) * frame.size)
            planes += frame.worldYAtMost(MgrsGridRenderer.mercY(c.latS) * frame.size)

            var xs = DoubleArray(ring.mercX.size) { frame.sx(ring.mercX[it], ring.mercY[it]) }
            var ys = DoubleArray(ring.mercX.size) { frame.sy(ring.mercX[it], ring.mercY[it]) }
            for (pl in planes) {
                val clipped = clipPolygon(xs, ys, pl)
                if (clipped == null) { xs = DoubleArray(0); break }
                xs = clipped.first; ys = clipped.second
            }
            if (xs.size < 3) continue
            val bw = xs.max() - xs.min()
            val bh = ys.max() - ys.min()
            if (bw < SQUARE_LABEL_MIN_DP || bh < SQUARE_LABEL_MIN_DP) continue
            val cen = areaCentroid(xs, ys) ?: continue

            var ti = 0
            for (i in xs.indices) if (xs[i] + ys[i] < xs[ti] + ys[ti]) ti = i
            val tx = xs[ti]
            val ty = ys[ti]
            var ax = tx + SQUARE_LABEL_OFFSET_DP
            var ay = ty + SQUARE_LABEL_OFFSET_DP
            if (!pointInPolygon(ax, ay, xs, ys)) {
                val d = hypot(cen[0] - tx, cen[1] - ty)
                val f = if (d > 0) min(1.0, SQUARE_LABEL_OFFSET_DP * sqrt(2.0) / d) else 0.0
                ax = tx + (cen[0] - tx) * f
                ay = ty + (cen[1] - ty) * f
            }
            val label = Label(
                isSquare = true, zone = c.zone, south = c.south, band = c.band,
                axis = null, value = 0, easting = ring.easting, northing = ring.northing,
                level = GridType.HUNDRED_KILOMETER, text = ring.text, x = ax, y = ay, rotated = false,
            )
            out += Candidate(label, frame.worldX(ax, ay), frame.worldY(ax, ay))
        }
    }

    // ---- small geometry helpers, half-planes are [a, b, c] with a*x + b*y + c >= 0 ----

    /** Parametric range [tIn, tOut] of the segment inside every plane, or null. */
    internal fun clipSegment(x0: Double, y0: Double, x1: Double, y1: Double, planes: List<DoubleArray>): DoubleArray? {
        var tIn = 0.0
        var tOut = 1.0
        for (p in planes) {
            val f0 = p[0] * x0 + p[1] * y0 + p[2]
            val f1 = p[0] * x1 + p[1] * y1 + p[2]
            if (f0 < 0 && f1 < 0) return null
            if (f0 < 0) tIn = max(tIn, f0 / (f0 - f1))
            else if (f1 < 0) tOut = min(tOut, f0 / (f0 - f1))
            if (tIn > tOut) return null
        }
        return doubleArrayOf(tIn, tOut)
    }

    /** Sutherland-Hodgman against one half-plane. null once nothing's left. */
    private fun clipPolygon(xs: DoubleArray, ys: DoubleArray, p: DoubleArray): Pair<DoubleArray, DoubleArray>? {
        val n = xs.size
        if (n == 0) return null
        // each crossing adds at most one point, so 2n always fits
        val ox = DoubleArray(2 * n)
        val oy = DoubleArray(2 * n)
        var k = 0
        for (i in 0 until n) {
            val j = if (i == 0) n - 1 else i - 1
            val fc = p[0] * xs[i] + p[1] * ys[i] + p[2]
            val fp = p[0] * xs[j] + p[1] * ys[j] + p[2]
            val cin = fc >= 0
            if (cin != (fp >= 0)) {
                val t = fp / (fp - fc)
                ox[k] = xs[j] + (xs[i] - xs[j]) * t
                oy[k] = ys[j] + (ys[i] - ys[j]) * t
                k++
            }
            if (cin) { ox[k] = xs[i]; oy[k] = ys[i]; k++ }
        }
        if (k == 0) return null
        return ox.copyOf(k) to oy.copyOf(k)
    }

    private fun areaCentroid(xs: DoubleArray, ys: DoubleArray): DoubleArray? {
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        val n = xs.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val c = xs[i] * ys[j] - xs[j] * ys[i]
            a += c
            cx += (xs[i] + xs[j]) * c
            cy += (ys[i] + ys[j]) * c
        }
        a *= 0.5
        if (abs(a) < 1e-12) return null
        return doubleArrayOf(cx / (6 * a), cy / (6 * a))
    }

    private fun pointInPolygon(x: Double, y: Double, xs: DoubleArray, ys: DoubleArray): Boolean {
        var inside = false
        val n = xs.size
        for (i in 0 until n) {
            val j = if (i == 0) n - 1 else i - 1
            if ((ys[i] > y) != (ys[j] > y)) {
                val xi = xs[i] + (y - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i])
                if (x < xi) inside = !inside
            }
        }
        return inside
    }
}

/**
 * MapCamera's projection unrolled for merc unit coords (0..1), so projecting a
 * few thousand cached grid vertices per frame is just a multiply-add. Matches
 * MapCamera.screenPoint exactly for any heading. Output is dp.
 */
internal class GridScreenFrame(camera: MapCamera) {
    val size = WebMercator.TILE_SIZE * 2.0.pow(camera.zoom)
    private val c = WebMercator.worldPoint(camera.centerLat, camera.centerLon, camera.zoom)
    private val h = Math.toRadians(camera.headingDegrees)
    private val cosH = if (camera.headingDegrees == 0.0) 1.0 else cos(h)
    private val sinH = if (camera.headingDegrees == 0.0) 0.0 else sin(h)
    private val halfW = camera.viewportWidth / 2
    private val halfH = camera.viewportHeight / 2
    // visible viewport's bbox in merc units, for cheap culling
    private val visMinX: Double
    private val visMaxX: Double
    private val visMinY: Double
    private val visMaxY: Double

    init {
        val cx = doubleArrayOf(0.0, camera.viewportWidth, camera.viewportWidth, 0.0)
        val cy = doubleArrayOf(0.0, 0.0, camera.viewportHeight, camera.viewportHeight)
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE
        var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0..3) {
            val wx = worldX(cx[i], cy[i]) / size
            val wy = worldY(cx[i], cy[i]) / size
            x0 = min(x0, wx); x1 = max(x1, wx); y0 = min(y0, wy); y1 = max(y1, wy)
        }
        // a few dp of slop for stroke width / round caps
        val pad = 8.0 / size
        visMinX = x0 - pad; visMaxX = x1 + pad; visMinY = y0 - pad; visMaxY = y1 + pad
    }

    fun sx(mx: Double, my: Double): Double = (mx * size - c.x) * cosH + (my * size - c.y) * sinH + halfW
    fun sy(mx: Double, my: Double): Double = -(mx * size - c.x) * sinH + (my * size - c.y) * cosH + halfH

    /** screen dp -> world px at the camera zoom (same units as size) */
    fun worldX(sx: Double, sy: Double): Double = (sx - halfW) * cosH - (sy - halfH) * sinH + c.x
    fun worldY(sx: Double, sy: Double): Double = (sx - halfW) * sinH + (sy - halfH) * cosH + c.y

    fun mayBeVisible(minX: Double, minY: Double, maxX: Double, maxY: Double): Boolean =
        maxX >= visMinX && minX <= visMaxX && maxY >= visMinY && minY <= visMaxY

    fun insetPlanes(inset: Double): List<DoubleArray> = listOf(
        doubleArrayOf(1.0, 0.0, -inset),
        doubleArrayOf(-1.0, 0.0, 2 * halfW - inset),
        doubleArrayOf(0.0, 1.0, -inset),
        doubleArrayOf(0.0, -1.0, 2 * halfH - inset),
    )

    fun worldXAtLeast(x: Double) = doubleArrayOf(cosH, -sinH, -halfW * cosH + halfH * sinH + c.x - x)
    fun worldXAtMost(x: Double) = doubleArrayOf(-cosH, sinH, halfW * cosH - halfH * sinH - c.x + x)
    fun worldYAtLeast(y: Double) = doubleArrayOf(sinH, cosH, -halfW * sinH - halfH * cosH + c.y - y)
    fun worldYAtMost(y: Double) = doubleArrayOf(-sinH, -cosH, halfW * sinH + halfH * cosH - c.y + y)
}
