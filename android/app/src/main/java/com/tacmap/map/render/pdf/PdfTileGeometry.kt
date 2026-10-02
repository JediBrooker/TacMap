package com.tacmap.map.render.pdf

import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfGeoreference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

// Pure maths behind the PDF tile source (plans/WP2-render-shared_contract.md C, D).
// No android imports in here on purpose, testdata/pdf_tile_render.json pins all of it
// on the JVM. Everything is Double, floats only show up when a Matrix gets built.

/** 2d affine, u = a*x + b*y + c ; v = d*x + e*y + f (same order as the fixture pageToPx) */
data class Affine2(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double,
) {
    fun mapX(x: Double, y: Double): Double = a * x + b * y + c
    fun mapY(x: Double, y: Double): Double = d * x + e * y + f

    val determinant: Double get() = a * e - b * d

    fun isFinite(): Boolean = a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite() && e.isFinite() && f.isFinite()

    fun inverse(): Affine2? {
        val det = determinant
        if (det == 0.0 || !det.isFinite()) return null
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine2(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f)).takeIf { it.isFinite() }
    }

    /** next after this: p -> next(this(p)) */
    fun then(next: Affine2): Affine2 = Affine2(
        next.a * a + next.b * d, next.a * b + next.b * e, next.a * c + next.b * f + next.c,
        next.d * a + next.e * d, next.d * b + next.e * e, next.d * c + next.e * f + next.f,
    )

    /** biggest column norm of the linear part, i.e. px per pt along the worse page axis */
    fun maxColumnNorm(): Double = max(hypot(a, d), hypot(b, e))

    fun toFloatArray9(): FloatArray = floatArrayOf(
        a.toFloat(), b.toFloat(), c.toFloat(),
        d.toFloat(), e.toFloat(), f.toFloat(),
        0f, 0f, 1f,
    )

    companion object {
        val IDENTITY = Affine2(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        fun translate(tx: Double, ty: Double) = Affine2(1.0, 0.0, tx, 0.0, 1.0, ty)
        fun scale(sx: Double, sy: Double) = Affine2(sx, 0.0, 0.0, 0.0, sy, 0.0)
    }
}

enum class TileCoverage { OUTSIDE, EDGE, INSIDE }

/** a block of tiles rendered in one go, cols x rows starting at (x0, y0) */
data class TileJob(val z: Int, val x0: Int, val y0: Int, val cols: Int, val rows: Int) {
    val tileCount: Int get() = cols * rows

    fun contains(z: Int, x: Int, y: Int): Boolean =
        z == this.z && x in x0 until x0 + cols && y in y0 until y0 + rows

    /** row major, the order every job hands its tiles back in */
    fun tiles(): List<IntArray> {
        val out = ArrayList<IntArray>(cols * rows)
        for (y in y0 until y0 + rows) for (x in x0 until x0 + cols) out += intArrayOf(z, x, y)
        return out
    }

    companion object {
        fun single(z: Int, x: Int, y: Int) = TileJob(z, x, y, 1, 1)
    }
}

/** why a PDF couldn't be drawn, shared reason codes (contract G) */
enum class PdfRenderFailure(val code: String) {
    CANNOT_OPEN("cannotOpen"),
    PASSWORD_PROTECTED("passwordProtected"),
    PAGE_MISSING("pageMissing"),
    PAGE_GEOMETRY("pageGeometry"),
    BLANK("blank"),
    OUT_OF_MEMORY("outOfMemory"),
    RENDER_ERROR("renderError");

    companion object {
        fun fromCode(code: String): PdfRenderFailure? = entries.firstOrNull { it.code == code }
    }
}

class PdfRenderException(val failure: PdfRenderFailure, cause: Throwable? = null) :
    Exception(failure.code, cause)

/** web mercator world units at z0 (0..256, y down), the footprint's space */
internal object MercatorWorld {
    const val EARTH_RADIUS = 6_378_137.0
    /** merc metres per 256-unit tile at z0 */
    const val METRES_PER_UNIT_Z0 = 2.0 * PI * EARTH_RADIUS / 256.0

    fun x(lon: Double): Double = (lon + 180.0) / 360.0 * 256.0

    fun y(lat: Double): Double {
        val s = ln(tan(PI / 4.0 + Math.toRadians(lat) / 2.0))
        return (1.0 - s / PI) / 2.0 * 256.0
    }

    /** contract A: job pixel -> lat/lon, out[0] = lat, out[1] = lon */
    fun jobPixelToLatLon(z: Int, x0: Int, y0: Int, tilePx: Int, u: Double, v: Double, out: DoubleArray) {
        val n = Math.scalb(1.0, z)
        val wx = x0 * 256.0 + u * 256.0 / tilePx
        val wy = y0 * 256.0 + v * 256.0 / tilePx
        out[1] = wx / (256.0 * n) * 360.0 - 180.0
        out[0] = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * wy / (256.0 * n)))))
    }

    /** merc metres (R*lon, R*ln(tan(pi/4 + lat/2))), radians inside */
    fun metres(lat: Double, lon: Double, out: DoubleArray) {
        out[0] = EARTH_RADIUS * Math.toRadians(lon)
        out[1] = EARTH_RADIUS * ln(tan(PI / 4.0 + Math.toRadians(lat) / 2.0))
    }
}

/** page space polygon helpers. clip = crop ∩ pageBox via Sutherland-Hodgman, exactly as the fixture */
object PdfClipPolygon {
    const val DEDUPE_EPS = 1e-9
    const val MIN_AREA_PT2 = 1.0

    /** edges in order x >= x0, x <= x1, y >= y0, y <= y1. on an edge counts as inside */
    fun clip(subject: List<PagePoint>, box: PdfBox): List<PagePoint> {
        var out: List<PagePoint> = subject
        for (edge in 0 until 4) {
            if (out.isEmpty()) break
            val src = out
            val next = ArrayList<PagePoint>(src.size + 4)
            var s = src.last()
            for (e in src) {
                val eIn = inside(e, edge, box)
                val sIn = inside(s, edge, box)
                if (eIn) {
                    if (!sIn) next += cut(s, e, edge, box)
                    next += e
                } else if (sIn) {
                    next += cut(s, e, edge, box)
                }
                s = e
            }
            out = next
        }
        val ded = ArrayList<PagePoint>(out.size)
        for (p in out) {
            val last = ded.lastOrNull()
            if (last != null && abs(p.x - last.x) <= DEDUPE_EPS && abs(p.y - last.y) <= DEDUPE_EPS) continue
            ded += p
        }
        while (ded.size > 1 && abs(ded[0].x - ded.last().x) <= DEDUPE_EPS && abs(ded[0].y - ded.last().y) <= DEDUPE_EPS) {
            ded.removeAt(ded.size - 1)
        }
        return ded
    }

    private fun inside(p: PagePoint, edge: Int, b: PdfBox): Boolean = when (edge) {
        0 -> p.x >= b.llx
        1 -> p.x <= b.urx
        2 -> p.y >= b.lly
        else -> p.y <= b.ury
    }

    private fun cut(s: PagePoint, e: PagePoint, edge: Int, b: PdfBox): PagePoint = when (edge) {
        0 -> PagePoint(b.llx, s.y + (e.y - s.y) * (b.llx - s.x) / (e.x - s.x))
        1 -> PagePoint(b.urx, s.y + (e.y - s.y) * (b.urx - s.x) / (e.x - s.x))
        2 -> PagePoint(s.x + (e.x - s.x) * (b.lly - s.y) / (e.y - s.y), b.lly)
        else -> PagePoint(s.x + (e.x - s.x) * (b.ury - s.y) / (e.y - s.y), b.ury)
    }

    /** shoelace, signed (ccw positive) */
    fun signedArea(poly: List<PagePoint>): Double {
        var a = 0.0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2.0
    }

    fun isConvex(poly: List<PagePoint>): Boolean {
        var sgn = 0
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[i]
            val b = poly[(i + 1) % n]
            val c = poly[(i + 2) % n]
            val cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cr) < 1e-12) continue
            val s = if (cr > 0) 1 else -1
            if (sgn != 0 && s != sgn) return false
            sgn = s
        }
        return true
    }

    /** contract C: n segments per edge, vertex k of edge i = P_i + (P_i+1 - P_i) * k/n */
    fun densify(poly: List<PagePoint>, n: Int = PdfFootprint.SEGMENTS_PER_EDGE): List<PagePoint> {
        val out = ArrayList<PagePoint>(poly.size * n)
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            for (k in 0 until n) {
                val t = k.toDouble() / n
                out += PagePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        return out
    }

    /** even-odd, same crossing rule as the generator's point_in_poly */
    fun contains(px: Double, py: Double, xs: DoubleArray, ys: DoubleArray): Boolean {
        var inside = false
        val n = xs.size
        for (i in 0 until n) {
            val x0 = xs[i]
            val y0 = ys[i]
            val j = if (i + 1 == n) 0 else i + 1
            val x1 = xs[j]
            val y1 = ys[j]
            if ((y0 > py) != (y1 > py)) {
                val xi = x0 + (py - y0) * (x1 - x0) / (y1 - y0)
                if (px < xi) inside = !inside
            }
        }
        return inside
    }

    /** closed segment vs closed axis aligned rect, liang-barsky (port of seg_hits_rect) */
    fun segmentHitsRect(px: Double, py: Double, qx: Double, qy: Double, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        if (x1 < x0 || y1 < y0) return false
        val dx = qx - px
        val dy = qy - py
        var t0 = 0.0
        var t1 = 1.0
        for (k in 0 until 4) {
            val pp: Double
            val qq: Double
            when (k) {
                0 -> { pp = -dx; qq = px - x0 }
                1 -> { pp = dx; qq = x1 - px }
                2 -> { pp = -dy; qq = py - y0 }
                else -> { pp = dy; qq = y1 - py }
            }
            if (pp == 0.0) {
                if (qq < 0) return false
            } else {
                val r = qq / pp
                if (pp < 0) {
                    if (r > t1) return false
                    t0 = max(t0, r)
                } else {
                    if (r < t0) return false
                    t1 = min(t1, r)
                }
            }
        }
        return t0 <= t1
    }
}

/**
 * The sheet's clip polygon in page space plus its outline projected into web
 * mercator (z0 world units). Decides which tiles get drawn at all and which job
 * pixels the alpha mask keeps. Built once per georef, immutable, thread safe.
 */
class PdfFootprint private constructor(
    /** crop ∩ (CropBox ∩ MediaBox), page space */
    val clip: List<PagePoint>,
    val pageBox: PdfBox,
    /** densified outline, z0 world units */
    val worldX: DoubleArray,
    val worldY: DoubleArray,
) {
    val clipMean: PagePoint = PagePoint(clip.sumOf { it.x } / clip.size, clip.sumOf { it.y } / clip.size)
    val clipArea: Double = abs(PdfClipPolygon.signedArea(clip))

    /** page bbox of the clip polygon [minX, minY, maxX, maxY] */
    val clipBounds: DoubleArray = doubleArrayOf(
        clip.minOf { it.x }, clip.minOf { it.y }, clip.maxOf { it.x }, clip.maxOf { it.y },
    )

    /** world bbox of the projected outline [minX, minY, maxX, maxY] */
    val worldBounds: DoubleArray = doubleArrayOf(worldX.min(), worldY.min(), worldX.max(), worldY.max())

    private val convex = PdfClipPolygon.isConvex(clip)
    private val ccw = PdfClipPolygon.signedArea(clip) > 0
    private val clipXs = DoubleArray(clip.size) { clip[it].x }
    private val clipYs = DoubleArray(clip.size) { clip[it].y }

    // per segment bbox so a tile test only looks at the few segments near it
    private val segMinX = DoubleArray(worldX.size)
    private val segMaxX = DoubleArray(worldX.size)
    private val segMinY = DoubleArray(worldX.size)
    private val segMaxY = DoubleArray(worldX.size)

    init {
        for (i in worldX.indices) {
            val j = (i + 1) % worldX.size
            segMinX[i] = min(worldX[i], worldX[j]); segMaxX[i] = max(worldX[i], worldX[j])
            segMinY[i] = min(worldY[i], worldY[j]); segMaxY[i] = max(worldY[i], worldY[j])
        }
    }

    /** tile square in z0 units, closed. ts = 256 / 2^z */
    private fun tileSize(z: Int): Double = 256.0 / Math.scalb(1.0, z)

    /** EDGE when the outline touches the square, INSIDE when the centre is in, else OUTSIDE */
    fun classify(z: Int, x: Int, y: Int): TileCoverage {
        if (z < 0 || z > 30) return TileCoverage.OUTSIDE
        val n = 1L shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return TileCoverage.OUTSIDE
        val ts = tileSize(z)
        val x0 = x * ts
        val y0 = y * ts
        val x1 = (x + 1) * ts
        val y1 = (y + 1) * ts
        if (x1 < worldBounds[0] || x0 > worldBounds[2] || y1 < worldBounds[1] || y0 > worldBounds[3]) {
            return TileCoverage.OUTSIDE
        }
        if (touches(x0, y0, x1, y1)) return TileCoverage.EDGE
        return if (PdfClipPolygon.contains((x + 0.5) * ts, (y + 0.5) * ts, worldX, worldY)) TileCoverage.INSIDE
        else TileCoverage.OUTSIDE
    }

    private fun touches(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        val n = worldX.size
        for (i in 0 until n) {
            if (segMaxX[i] < x0 || segMinX[i] > x1 || segMaxY[i] < y0 || segMinY[i] > y1) continue
            val j = if (i + 1 == n) 0 else i + 1
            if (PdfClipPolygon.segmentHitsRect(worldX[i], worldY[i], worldX[j], worldY[j], x0, y0, x1, y1)) return true
        }
        return false
    }

    /**
     * A level's non-OUTSIDE tiles, sorted by y then x. [truncated] means the scan stopped
     * once it had more than the caller's limit, so [count] is only a lower bound then.
     */
    class Level(
        val z: Int,
        val xs: IntArray,
        val ys: IntArray,
        val coverage: Array<TileCoverage>,
        val truncated: Boolean = false,
    ) {
        val count: Int get() = xs.size
        val insideCount: Int get() = coverage.count { it == TileCoverage.INSIDE }
    }

    /**
     * Every tile at z that intersects the footprint. Scanline for the inside test so big
     * levels stay cheap. Stops early (truncated) once it has more than [limit] tiles, so a
     * hostile sheet can't make the bake estimate eat the heap (S2). [isActive] gets polled
     * as it goes, false bails out with a CancellationException.
     */
    fun level(z: Int, limit: Int = Int.MAX_VALUE, isActive: () -> Boolean = { true }): Level {
        val n = 1L shl z
        val ts = tileSize(z)
        val edge = LongSet()
        val m = worldX.size
        var polled = 0L
        fun poll() {
            if (++polled and 0xffffL == 0L && !isActive()) throw kotlinx.coroutines.CancellationException("level scan cancelled")
        }
        // the footprint's own tile range, the edge scan never leaves it
        val bx0 = max(0L, floor(worldBounds[0] / ts).toLong())
        val bx1 = min(n - 1, floor(worldBounds[2] / ts).toLong())
        val by0 = max(0L, floor(worldBounds[1] / ts).toLong())
        val by1 = min(n - 1, floor(worldBounds[3] / ts).toLong())
        for (i in 0 until m) {
            val j = (i + 1) % m
            // +-1 column and row like iOS, a segment sitting right on a tile line still
            // gets both neighbours tested. segmentHitsRect decides
            val ix0 = max(bx0, floor(segMinX[i] / ts).toLong() - 1)
            val ix1 = min(bx1, floor(segMaxX[i] / ts).toLong() + 1)
            val iy0 = max(by0, floor(segMinY[i] / ts).toLong() - 1)
            val iy1 = min(by1, floor(segMaxY[i] / ts).toLong() + 1)
            if (ix0 > ix1 || iy0 > iy1) continue
            val ax = worldX[i]; val ay = worldY[i]; val bx = worldX[j]; val by = worldY[j]
            for (ix in ix0..ix1) {
                // only the rows the segment can reach inside this column (+-1 for rounding),
                // a long diagonal over a huge bbox would otherwise walk rows x cols (S2).
                // segmentHitsRect still decides, so the result is the same set as before
                var jy0 = iy0
                var jy1 = iy1
                if (bx != ax) {
                    val sx0 = max(segMinX[i], ix * ts)
                    val sx1 = min(segMaxX[i], (ix + 1) * ts)
                    val ya = ay + (sx0 - ax) * (by - ay) / (bx - ax)
                    val yb = ay + (sx1 - ax) * (by - ay) / (bx - ax)
                    if (ya.isFinite() && yb.isFinite()) {
                        jy0 = max(iy0, floor(min(ya, yb) / ts).toLong() - 1)
                        jy1 = min(iy1, floor(max(ya, yb) / ts).toLong() + 1)
                    }
                }
                for (iy in jy0..jy1) {
                    poll()
                    if (PdfClipPolygon.segmentHitsRect(ax, ay, bx, by, ix * ts, iy * ts, (ix + 1) * ts, (iy + 1) * ts)) {
                        edge.add(pack(ix, iy))
                        // every edge tile is a level tile, no point going on
                        if (edge.size > limit) return Level(z, IntArray(edge.size), IntArray(edge.size), Array(edge.size) { TileCoverage.EDGE }, truncated = true)
                    }
                }
            }
        }
        val xs = IntList()
        val ys = IntList()
        val cov = ArrayList<TileCoverage>()
        val rx0 = max(0L, floor(worldBounds[0] / ts).toLong())
        val rx1 = min(n - 1, floor(worldBounds[2] / ts).toLong())
        val ry0 = max(0L, floor(worldBounds[1] / ts).toLong())
        val ry1 = min(n - 1, floor(worldBounds[3] / ts).toLong())
        val crossings = DoubleArray(m)
        for (iy in ry0..ry1) {
            if (!isActive()) throw kotlinx.coroutines.CancellationException("level scan cancelled")
            val cy = (iy + 0.5) * ts
            var k = 0
            for (i in 0 until m) {
                val j = if (i + 1 == m) 0 else i + 1
                val ya = worldY[i]
                val yb = worldY[j]
                if ((ya > cy) != (yb > cy)) {
                    crossings[k++] = worldX[i] + (cy - ya) * (worldX[j] - worldX[i]) / (yb - ya)
                }
            }
            java.util.Arrays.sort(crossings, 0, k)
            for (ix in rx0..rx1) {
                if (edge.contains(pack(ix, iy))) {
                    xs.add(ix.toInt()); ys.add(iy.toInt()); cov += TileCoverage.EDGE
                } else {
                    val cx = (ix + 0.5) * ts
                    // inside when an odd number of crossings sit strictly right of the centre
                    var lo = 0
                    var hi = k
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (crossings[mid] <= cx) lo = mid + 1 else hi = mid
                    }
                    if ((k - lo) % 2 == 1) {
                        xs.add(ix.toInt()); ys.add(iy.toInt()); cov += TileCoverage.INSIDE
                    } else continue
                }
                if (xs.size > limit) return Level(z, xs.toArray(), ys.toArray(), cov.toTypedArray(), truncated = true)
            }
        }
        return Level(z, xs.toArray(), ys.toArray(), cov.toTypedArray())
    }

    /** just the count, for the bake estimate. past [limit] it's a lower bound (> limit) */
    fun count(z: Int, limit: Int = Int.MAX_VALUE, isActive: () -> Boolean = { true }): Int = level(z, limit, isActive).count

    /** footprint outline in job pixels [minU, minV, maxU, maxV] */
    fun jobPixelBounds(job: TileJob, tilePx: Int): DoubleArray {
        val n = Math.scalb(1.0, job.z)
        val k = tilePx / 256.0
        var minU = Double.POSITIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY
        var maxU = Double.NEGATIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        for (i in worldX.indices) {
            val u = (worldX[i] * n - job.x0 * 256.0) * k
            val v = (worldY[i] * n - job.y0 * 256.0) * k
            minU = min(minU, u); maxU = max(maxU, u)
            minV = min(minV, v); maxV = max(maxV, v)
        }
        return doubleArrayOf(minU, minV, maxU, maxV)
    }

    /** the projected outline in job pixels, for the alpha mask path. (u0, v0, u1, v1, ...) */
    fun jobPixelOutline(job: TileJob, tilePx: Int): DoubleArray {
        val n = Math.scalb(1.0, job.z)
        val k = tilePx / 256.0
        val out = DoubleArray(worldX.size * 2)
        for (i in worldX.indices) {
            out[2 * i] = (worldX[i] * n - job.x0 * 256.0) * k
            out[2 * i + 1] = (worldY[i] * n - job.y0 * 256.0) * k
        }
        return out
    }

    /** page quad (TL, TR, BR, BL) of a warp cell vs the clip polygon */
    fun classifyQuad(quad: List<PagePoint>): TileCoverage {
        var q = quad
        if (PdfClipPolygon.signedArea(q) < 0) q = q.asReversed()
        if (convex) {
            var margin = Double.POSITIVE_INFINITY
            for (v in q) margin = min(margin, convexSignedDistance(v.x, v.y))
            if (margin > 0) return TileCoverage.INSIDE
            if (satSeparation(q, clip) > 0) return TileCoverage.OUTSIDE
            return TileCoverage.EDGE
        }
        // a non convex crop (a /Bounds polygon could be): plain crossing tests
        val allIn = q.all { PdfClipPolygon.contains(it.x, it.y, clipXs, clipYs) }
        val crosses = edgesCross(q, clip)
        if (allIn && !crosses) return TileCoverage.INSIDE
        if (!crosses && q.none { PdfClipPolygon.contains(it.x, it.y, clipXs, clipYs) } &&
            clip.none { pointInQuad(it, q) }) return TileCoverage.OUTSIDE
        return TileCoverage.EDGE
    }

    /** distance to the nearest edge line, positive inside. exact for inside points of a convex polygon */
    private fun convexSignedDistance(px: Double, py: Double): Double {
        var best = Double.POSITIVE_INFINITY
        val n = clip.size
        for (i in 0 until n) {
            val a = clip[i]
            val b = clip[(i + 1) % n]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = hypot(ex, ey)
            val cr = (ex * (py - a.y) - ey * (px - a.x)) / len
            best = min(best, if (ccw) cr else -cr)
        }
        return best
    }

    private fun satSeparation(a: List<PagePoint>, b: List<PagePoint>): Double {
        var best = Double.NEGATIVE_INFINITY
        for (poly in listOf(a, b)) {
            val n = poly.size
            for (i in 0 until n) {
                val p = poly[i]
                val q = poly[(i + 1) % n]
                var nx = -(q.y - p.y)
                var ny = q.x - p.x
                val len = hypot(nx, ny)
                if (len == 0.0) continue
                nx /= len; ny /= len
                var aMin = Double.POSITIVE_INFINITY; var aMax = Double.NEGATIVE_INFINITY
                for (v in a) { val d = nx * v.x + ny * v.y; aMin = min(aMin, d); aMax = max(aMax, d) }
                var bMin = Double.POSITIVE_INFINITY; var bMax = Double.NEGATIVE_INFINITY
                for (v in b) { val d = nx * v.x + ny * v.y; bMin = min(bMin, d); bMax = max(bMax, d) }
                best = max(best, max(bMin - aMax, aMin - bMax))
            }
        }
        return best
    }

    private fun edgesCross(a: List<PagePoint>, b: List<PagePoint>): Boolean {
        for (i in a.indices) {
            val p = a[i]; val q = a[(i + 1) % a.size]
            for (j in b.indices) {
                val r = b[j]; val s = b[(j + 1) % b.size]
                if (segmentsIntersect(p, q, r, s)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(p: PagePoint, q: PagePoint, r: PagePoint, s: PagePoint): Boolean {
        fun orient(a: PagePoint, b: PagePoint, c: PagePoint) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val d1 = orient(r, s, p)
        val d2 = orient(r, s, q)
        val d3 = orient(p, q, r)
        val d4 = orient(p, q, s)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
    }

    private fun pointInQuad(p: PagePoint, q: List<PagePoint>): Boolean =
        PdfClipPolygon.contains(p.x, p.y, DoubleArray(q.size) { q[it].x }, DoubleArray(q.size) { q[it].y })

    companion object {
        const val SEGMENTS_PER_EDGE = 32

        private fun pack(x: Long, y: Long): Long = (x shl 32) or (y and 0xffffffffL)

        /**
         * crop ∩ pageBox, densified 32 per edge through toWGS84. Throws pageGeometry when the
         * clip has under 1 pt² of area, a non finite vertex, or an outline point off the earth.
         */
        fun build(georef: PdfGeoreference, pageBox: PdfBox): PdfFootprint =
            build(georef.crop, pageBox) { x, y ->
                georef.toWGS84(x, y)?.let { doubleArrayOf(it.latitude, it.longitude) }
            }

        fun build(crop: List<PagePoint>, pageBox: PdfBox, toWgs84: (Double, Double) -> DoubleArray?): PdfFootprint {
            val clip = PdfClipPolygon.clip(crop, pageBox)
            if (clip.size < 3 || clip.any { !it.isFinite() } ||
                !(abs(PdfClipPolygon.signedArea(clip)) >= PdfClipPolygon.MIN_AREA_PT2)
            ) throw PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)
            val dense = PdfClipPolygon.densify(clip)
            val xs = DoubleArray(dense.size)
            val ys = DoubleArray(dense.size)
            for ((i, p) in dense.withIndex()) {
                val ll = toWgs84(p.x, p.y) ?: throw PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)
                if (!ll[0].isFinite() || !ll[1].isFinite() || abs(ll[0]) >= 90.0) {
                    throw PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)
                }
                xs[i] = MercatorWorld.x(ll[1])
                ys[i] = MercatorWorld.y(ll[0])
                if (!xs[i].isFinite() || !ys[i].isFinite()) throw PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)
            }
            return PdfFootprint(clip, pageBox, xs, ys)
        }
    }
}

/** one leaf of the warp plan */
class PdfWarpCell(
    /** [l, t, r, b] job pixels, r/b exclusive */
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val pageToPx: Affine2,
    val errorPx: Double,
    val coverage: TileCoverage,
    /** page points under TL, TR, BR, BL */
    val quad: List<PagePoint>,
    val depth: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

class PdfWarpPlan(
    val job: TileJob,
    val tilePx: Int,
    /** [l, t, r, b] or null when the footprint misses the job */
    val root: IntArray?,
    val maxDepth: Int,
    /** every leaf, depth first TL, TR, BL, BR. OUTSIDE ones included */
    val leaves: List<PdfWarpCell>,
    /** non finite / singular rects at the split limit, left transparent */
    val dropped: Int,
) {
    /** the ones that actually get drawn */
    val cells: List<PdfWarpCell> = leaves.filter { it.coverage != TileCoverage.OUTSIDE }
    val maxErrorPx: Double = cells.maxOfOrNull { it.errorPx } ?: 0.0
    val errorBoundMet: Boolean = cells.all { it.errorPx <= PdfTileWarp.MAX_ERROR_PX }
    /** px per page pt the source has to deliver, max column norm over the cells */
    val requiredPxPerPt: Double = cells.maxOfOrNull { it.pageToPx.maxColumnNorm() } ?: 0.0
}

/**
 * Adaptive warp planner (contract D): split the job rect until a 3 corner affine
 * px -> page is within 0.0625 px at the 4th corner and the centre. Port of the
 * generator's plan_warp, cells come out in the same order.
 */
object PdfTileWarp {
    const val MAX_ERROR_PX = 0.0625
    const val BASE_DEPTH = 4
    const val MIN_CELL_PX = 32
    const val ROOT_PAD_PX = 2

    fun maxDepth(job: TileJob): Int = BASE_DEPTH + ceil(log2(max(job.cols, job.rows).toDouble())).toInt()

    fun plan(job: TileJob, tilePx: Int, footprint: PdfFootprint, georef: PdfGeoreference): PdfWarpPlan =
        plan(job, tilePx, footprint) { lat, lon -> georef.toPage(lat, lon) }

    fun plan(
        job: TileJob,
        tilePx: Int,
        footprint: PdfFootprint,
        toPage: (lat: Double, lon: Double) -> PagePoint?,
    ): PdfWarpPlan {
        val w = job.cols * tilePx
        val h = job.rows * tilePx
        val bounds = footprint.jobPixelBounds(job, tilePx)
        val l = max(0, floor(bounds[0]).toInt() - ROOT_PAD_PX)
        val r = min(w, ceil(bounds[2]).toInt() + ROOT_PAD_PX)
        val t = max(0, floor(bounds[1]).toInt() - ROOT_PAD_PX)
        val b = min(h, ceil(bounds[3]).toInt() + ROOT_PAD_PX)
        val maxDepth = maxDepth(job)
        if (r <= l || b <= t) return PdfWarpPlan(job, tilePx, null, maxDepth, emptyList(), 0)

        val cache = HashMap<Long, PagePoint?>()
        val ll = DoubleArray(2)
        fun page(u: Int, v: Int, half: Boolean = false, uh: Double = 0.0, vh: Double = 0.0): PagePoint? {
            // corners are integers, the centre is a half integer at most. key on doubled coords
            val du = if (half) uh else u.toDouble()
            val dv = if (half) vh else v.toDouble()
            val key = ((2.0 * du).toLong() shl 32) or ((2.0 * dv).toLong() and 0xffffffffL)
            if (cache.containsKey(key)) return cache[key]
            MercatorWorld.jobPixelToLatLon(job.z, job.x0, job.y0, tilePx, du, dv, ll)
            val p = toPage(ll[0], ll[1])?.takeIf { it.isFinite() }
            cache[key] = p
            return p
        }

        val leaves = ArrayList<PdfWarpCell>()
        var dropped = 0

        fun rec(l: Int, t: Int, r: Int, b: Int, depth: Int) {
            val cw = r - l
            val ch = b - t
            val cu = (l + r) / 2.0
            val cv = (t + b) / 2.0
            val pTL = page(l, t)
            val pTR = page(r, t)
            val pBL = page(l, b)
            val pBR = page(r, b)
            val pC = page(0, 0, true, cu, cv)
            val canSplit = depth < maxDepth && cw >= MIN_CELL_PX && ch >= MIN_CELL_PX
            var inv: Affine2? = null
            var err = 0.0
            if (pTL != null && pTR != null && pBL != null && pBR != null && pC != null) {
                val a11 = (pTR.x - pTL.x) / cw
                val a12 = (pBL.x - pTL.x) / ch
                val a21 = (pTR.y - pTL.y) / cw
                val a22 = (pBL.y - pTL.y) / ch
                val ox = pTL.x - a11 * l - a12 * t
                val oy = pTL.y - a21 * l - a22 * t
                val det = a11 * a22 - a12 * a21
                if (det != 0.0 && det.isFinite()) {
                    val ia = a22 / det
                    val ib = -a12 / det
                    val id = -a21 / det
                    val ie = a11 / det
                    val m = Affine2(ia, ib, -(ia * ox + ib * oy), id, ie, -(id * ox + ie * oy))
                    val brU = m.mapX(pBR.x, pBR.y)
                    val brV = m.mapY(pBR.x, pBR.y)
                    val cU = m.mapX(pC.x, pC.y)
                    val cV = m.mapY(pC.x, pC.y)
                    err = max(hypot(brU - r, brV - b), hypot(cU - cu, cV - cv))
                    if (err.isFinite()) inv = m
                }
            }
            val split = if (inv == null) canSplit else err > MAX_ERROR_PX && canSplit
            if (split) {
                // children TL, TR, BL, BR, same order the fixture lists them
                val mx = l + cw / 2
                val my = t + ch / 2
                rec(l, t, mx, my, depth + 1)
                rec(mx, t, r, my, depth + 1)
                rec(l, my, mx, b, depth + 1)
                rec(mx, my, r, b, depth + 1)
                return
            }
            if (inv == null) {
                dropped++
                return
            }
            val quad = listOf(pTL!!, pTR!!, pBR!!, pBL!!)
            leaves += PdfWarpCell(l, t, r, b, inv, err, footprint.classifyQuad(quad), quad, depth)
        }

        rec(l, t, r, b, 0)
        return PdfWarpPlan(job, tilePx, intArrayOf(l, t, r, b), maxDepth, leaves, dropped)
    }
}

class PdfBaseRasterPlan(
    /** page rect [x0, y0, x1, y1] the raster covers, the clip bbox */
    val region: DoubleArray,
    val scale: Double,
    val width: Int,
    val height: Int,
    /** finest first, ceil halving until the long side is <= 256 */
    val mips: List<IntArray>,
) {
    val regionWidth: Double get() = region[2] - region[0]
    val regionHeight: Double get() = region[3] - region[1]
    /** raster px per pt, the worse axis */
    val densityPxPerPt: Double get() = min(width / regionWidth, height / regionHeight)

    fun mipDensity(level: Int): Double = min(mips[level][0] / regionWidth, mips[level][1] / regionHeight)

    /** smallest mip that still gives at least [required] px per pt, the finest one if none do */
    fun mipFor(required: Double): Int {
        var best = 0
        for (i in mips.indices) if (mipDensity(i) >= required) best = i
        return best
    }

    /** page -> mip level pixels (y down), region onto [0, W] x [0, H] exactly */
    fun pageToMip(level: Int): Affine2 {
        val sx = mips[level][0] / regionWidth
        val sy = mips[level][1] / regionHeight
        return Affine2(sx, 0.0, -region[0] * sx, 0.0, -sy, region[3] * sy)
    }

    /** a stable key for handing a probe raster to the live session */
    val key: String get() = "${region.joinToString(",")}|$width|$height"
}

/**
 * Zoom policy (contract C): how many merc metres one page point covers at the clip
 * centre, the detail zoom where a dp covers 1/4 pt, and the base raster budget.
 */
class PdfZoomPolicy(
    val mercMetresPerPoint: Double,
    /** [[dX/dx, dX/dy], [dY/dx, dY/dy]] merc metres per pt */
    val jacobian: Array<DoubleArray>,
    val detailZoomRaw: Double,
    val detailZoom: Int,
) {
    val minZoom: Int get() = 0
    val maxZoom: Int get() = detailZoom

    fun pxPerPoint(z: Int, tilePx: Int): Double = pxPerPoint(mercMetresPerPoint, z, tilePx)

    fun baseRasterPlan(footprint: PdfFootprint, budgetPx: Int): PdfBaseRasterPlan {
        val region = footprint.clipBounds.copyOf()
        val w = region[2] - region[0]
        val h = region[3] - region[1]
        val s = minOf(sqrt(budgetPx / (w * h)), BASE_MAX_SIDE / max(w, h), BASE_MAX_SCALE)
        val bw = max(1, ceil(w * s).toInt())
        val bh = max(1, ceil(h * s).toInt())
        val mips = ArrayList<IntArray>()
        mips += intArrayOf(bw, bh)
        while (max(mips.last()[0], mips.last()[1]) > MIP_STOP_SIDE) {
            val last = mips.last()
            mips += intArrayOf(ceil(last[0] / 2.0).toInt(), ceil(last[1] / 2.0).toInt())
        }
        return PdfBaseRasterPlan(region, s, bw, bh, mips)
    }

    /** largest z in [0, detailZoom] the base raster serves without upsampling, -1 if none */
    fun baseMaxZoom(plan: PdfBaseRasterPlan, tilePx: Int): Int {
        val density = plan.densityPxPerPt
        var best = -1
        for (z in 0..detailZoom) if (pxPerPoint(z, tilePx) <= density * UPSAMPLE_TOLERANCE) best = z
        return best
    }

    companion object {
        const val DETAIL_OVERSAMPLE = 4.0
        const val DETAIL_HYSTERESIS = 0.05
        const val MAX_ZOOM_CAP = 22
        const val JACOBIAN_STEP_PT = 1.0
        const val BUDGET_PX = 6_000_000
        const val LOW_RAM_BUDGET_PX = 3_000_000
        const val BASE_MAX_SIDE = 4096.0
        const val BASE_MAX_SCALE = 4.0
        const val MIP_STOP_SIDE = 256
        const val UPSAMPLE_TOLERANCE = 1.0
        const val DENSITY_CAP = 3.0
        const val TILE_PX_QUANTUM = 16

        /** round(256 * min(density, 3) / 16) * 16, ties up */
        fun tilePx(density: Double): Int {
            val d = if (density.isFinite() && density > 0) min(density, DENSITY_CAP) else 1.0
            return floor(256.0 * d / TILE_PX_QUANTUM + 0.5).toInt() * TILE_PX_QUANTUM
        }

        fun pxPerPoint(mercMetresPerPoint: Double, z: Int, tilePx: Int): Double =
            (tilePx / 256.0) * mercMetresPerPoint * Math.scalb(1.0, z) / MercatorWorld.METRES_PER_UNIT_Z0

        fun of(georef: PdfGeoreference, footprint: PdfFootprint): PdfZoomPolicy =
            of(footprint.clipMean) { x, y -> georef.toWGS84(x, y)?.let { doubleArrayOf(it.latitude, it.longitude) } }

        /** central differences (h = 1 pt) of page -> merc metres at [c] */
        fun of(c: PagePoint, toWgs84: (Double, Double) -> DoubleArray?): PdfZoomPolicy {
            val h = JACOBIAN_STEP_PT
            val m = DoubleArray(2)
            fun f(x: Double, y: Double): DoubleArray {
                val ll = toWgs84(x, y) ?: throw PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)
                MercatorWorld.metres(ll[0], ll[1], m)
                return doubleArrayOf(m[0], m[1])
            }
            val fxp = f(c.x + h, c.y)
            val fxm = f(c.x - h, c.y)
            val fyp = f(c.x, c.y + h)
            val fym = f(c.x, c.y - h)
            val j = arrayOf(
                doubleArrayOf((fxp[0] - fxm[0]) / 2.0, (fyp[0] - fym[0]) / 2.0),
                doubleArrayOf((fxp[1] - fxm[1]) / 2.0, (fyp[1] - fym[1]) / 2.0),
            )
            val det = j[0][0] * j[1][1] - j[0][1] * j[1][0]
            val mmpp = sqrt(abs(det))
            if (!mmpp.isFinite() || mmpp <= 0.0) throw PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)
            val raw = log2(DETAIL_OVERSAMPLE * MercatorWorld.METRES_PER_UNIT_Z0 / mmpp)
            val dz = ceil(raw - DETAIL_HYSTERESIS).coerceIn(0.0, MAX_ZOOM_CAP.toDouble()).toInt()
            return PdfZoomPolicy(mmpp, j, raw, dz)
        }
    }
}

/** growable int array, so a big level doesn't box every index */
internal class IntList(capacity: Int = 64) {
    private var a = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray(): IntArray = a.copyOf(size)
}

/** open addressing set of packed tile keys, no boxing. never holds Long.MIN_VALUE (unused slot) */
internal class LongSet(capacity: Int = 256) {
    private var keys = LongArray(Integer.highestOneBit(maxOf(16, capacity) * 2 - 1)).also { it.fill(FREE) }
    var size = 0
        private set

    private fun slot(k: Long, table: LongArray): Int {
        var h = (k * -0x61c8864680b583ebL).let { (it xor (it ushr 32)).toInt() } and (table.size - 1)
        while (table[h] != FREE && table[h] != k) h = (h + 1) and (table.size - 1)
        return h
    }

    fun add(k: Long): Boolean {
        val i = slot(k, keys)
        if (keys[i] == k) return false
        keys[i] = k
        size++
        if (size * 2 > keys.size) grow()
        return true
    }

    fun contains(k: Long): Boolean = keys[slot(k, keys)] == k

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2).also { it.fill(FREE) }
        for (k in old) if (k != FREE) keys[slot(k, keys)] = k
    }

    private companion object {
        const val FREE = Long.MIN_VALUE
    }
}
