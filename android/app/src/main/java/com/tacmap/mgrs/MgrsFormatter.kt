package com.tacmap.mgrs

import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import java.util.Locale

/**
 * Wrapper around NGA mgrs library. Everything in TacticalMaps stores
 * WGS84 internally, MGRS is just for display. Bare (lat, lng) doubles
 * so we don't depend on any map SDK.
 */
object MgrsFormatter {

    /** 1-metre precision (10-digit) MGRS readout. */
    val defaultPrecision: GridType = GridType.METER

    /** Out-of-UTM-range marker for polar latitudes, or null when in range. */
    private fun outOfRangeMarker(lat: Double): String? = when {
        lat > 84.0 -> "N/A (>84°N)"
        lat < -80.0 -> "N/A (<80°S)"
        else -> null
    }

    fun format(lat: Double, lng: Double,
               precision: GridType = defaultPrecision,
               spaced: Boolean = true): String {
        // UTM/MGRS only defined for 80S-84N. Past that, NGA silently clamps
        // to band X/C which can be ~110km off. Show out-of-range instead.
        outOfRangeMarker(lat)?.let { return it }
        val mgrs = MGRS.from(Point.point(lng, lat))
        val raw = mgrs.coordinate(precision)
        val compact = raw.replace("\\s+".toRegex(), "")
        return if (spaced) compact.withDisplaySpacing() else compact
    }

    /** UTM readout like "33N 450000mE 6700000mN". Hemisphere from lat sign,
     *  zone/easting/northing from NGA toUTM(). */
    fun formatUtm(lat: Double, lng: Double): String {
        outOfRangeMarker(lat)?.let { return it }
        val utm = MGRS.from(Point.point(lng, lat)).toUTM()
        val hemi = if (lat >= 0) "N" else "S"
        // Locale.US b/c default locale emits non-ASCII digits on
        // Arabic/Farsi/Bengali and would diverge from iOS.
        return "%02d%s %.0fmE %.0fmN".format(Locale.US, utm.zone, hemi, utm.easting, utm.northing)
    }

    /** Parse "56H LH 12345 67890" to (lat, lng). Returns null on bad input
     *  or partial strings so we don't resolve to some random grid-zone corner. */
    fun parse(s: String): Pair<Double, Double>? {
        val compact = s.replace(Regex("\\s+"), "").uppercase(Locale.US)
        if (!looksLikeMgrs(compact)) return null
        return try {
            val m = MGRS.parse(compact)
            val p = m.toPoint()
            p.latitude to p.longitude
        } catch (_: Throwable) {
            null
        }
    }

    /** Structural MGRS check matching iOS looksLikeMGRS. Zone 1-60, band C-X
     *  (no I/O), two 100km letters (no I/O), optional even easting/northing.
     *  Rejects partial/invalid input that NGA would resolve to a far corner. */
    fun looksLikeMgrs(s: String): Boolean {
        if (s.isEmpty()) return false
        val utm = Regex("^(0?[1-9]|[1-5]\\d|60)[C-HJ-NP-X][A-HJ-NP-Z][A-HJ-NP-Z](\\d{2}|\\d{4}|\\d{6}|\\d{8}|\\d{10})?$")
        val ups = Regex("^[ABYZ][A-HJ-NP-Z][A-HJ-NP-Z](\\d{2}|\\d{4}|\\d{6}|\\d{8}|\\d{10})?$")
        return utm.matches(s) || ups.matches(s)
    }

    private fun String.withDisplaySpacing(): String {
        // UTM (zone+band+square) or UPS polar (leading A/B/Y/Z + two square
        // letters). Matching UPS too keeps polar readouts spaced like iOS.
        val match = Regex("^(\\d{1,2}[A-Z][A-Z]{2})(\\d+)$").matchEntire(this)
            ?: Regex("^([ABYZ][A-Z]{2})(\\d+)$").matchEntire(this)
            ?: return this
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        // Only split an even digit run into easting/northing halves.
        if (digits.length % 2 != 0) return "$prefix $digits"
        val split = digits.length / 2
        return "$prefix ${digits.take(split)} ${digits.drop(split)}"
    }
}
