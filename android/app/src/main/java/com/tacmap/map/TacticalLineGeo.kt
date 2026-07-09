package com.tacmap.map

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/// Geographic-space decoration for NATO tactical lines - Android equivalent
/// of iOS TacticalLineRenderer. Produces crenellated FLOT lines, boundary
/// ticks, axis arrowheads etc that the Polyline renderer strokes. Sizes in
/// metres so decoration scales with zoom.
object TacticalLineGeo {
    private const val M_PER_DEG = 111_320.0
    private fun mPerLng(lat: Double) = M_PER_DEG * cos(Math.toRadians(lat)).coerceAtLeast(1e-6)

    private data class Samp(val p: LatLng, val nx: Double, val ny: Double, val s: Double)

    /// Walk the polyline at stepM spacing, get point + left-normal + cumulative
    /// arc length. All in metre space.
    private fun sample(pts: List<LatLng>, stepM: Double): List<Samp> {
        val out = ArrayList<Samp>()
        var s = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val mLng = mPerLng((a.latitude + b.latitude) / 2)
            val dxm = (b.longitude - a.longitude) * mLng
            val dym = (b.latitude - a.latitude) * M_PER_DEG
            val len = hypot(dxm, dym).coerceAtLeast(1e-6)
            val ux = dxm / len; val uy = dym / len
            val nx = -uy; val ny = ux                       // left normal
            var t = 0.0
            while (t < len) {
                val f = t / len
                out.add(Samp(LatLng(a.latitude + (b.latitude - a.latitude) * f,
                                    a.longitude + (b.longitude - a.longitude) * f), nx, ny, s + t))
                t += stepM
            }
            s += len
        }
        out.add(Samp(pts.last(), 0.0, 0.0, s))
        return out
    }

    private fun offset(p: LatLng, nx: Double, ny: Double, d: Double) =
        LatLng(p.latitude + (ny * d) / M_PER_DEG, p.longitude + (nx * d) / mPerLng(p.latitude))

    /// Square-wave (battlement) line for FLOT / FEBA forward edge.
    fun crenellate(pts: List<LatLng>, toothM: Double = 35.0, periodM: Double = 72.0): List<LatLng> {
        if (pts.size < 2) return pts
        val half = periodM / 2
        return sample(pts, periodM / 8).map {
            if ((it.s / half).toInt() % 2 == 1) offset(it.p, it.nx, it.ny, toothM) else it.p
        }
    }

    /// Perpendicular tick marks at intervals. Boundary line style.
    fun boundaryTicks(pts: List<LatLng>, spacingM: Double = 95.0, lenM: Double = 26.0): List<List<LatLng>> {
        if (pts.size < 2) return emptyList()
        val ticks = ArrayList<List<LatLng>>()
        var next = spacingM
        for (it in sample(pts, spacingM / 6)) if (it.s >= next) {
            ticks.add(listOf(offset(it.p, it.nx, it.ny, lenM), offset(it.p, it.nx, it.ny, -lenM)))
            next += spacingM
        }
        return ticks
    }

    /// Arrowhead (wing-tip-wing) at end of the line.
    fun arrowHead(pts: List<LatLng>, sizeM: Double = 60.0): List<LatLng> {
        if (pts.size < 2) return emptyList()
        val tip = pts.last(); val prev = pts[pts.size - 2]
        val mLng = mPerLng(tip.latitude)
        val ang = atan2((tip.latitude - prev.latitude) * M_PER_DEG,
                        (tip.longitude - prev.longitude) * mLng)
        fun wing(da: Double): LatLng {
            val wx = cos(ang + da) * sizeM; val wy = sin(ang + da) * sizeM
            return LatLng(tip.latitude + wy / M_PER_DEG, tip.longitude + wx / mLng)
        }
        return listOf(wing(Math.PI * 0.83), tip, wing(-Math.PI * 0.83))
    }
}
