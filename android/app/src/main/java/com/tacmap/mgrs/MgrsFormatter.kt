package com.tacmap.mgrs

import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import java.util.Locale

/**
 * Wrapper around NGA `mgrs` library. All overlays in TacticalMaps store
 * WGS84; MGRS strings are presentation only.
 *
 * Inputs are bare (lat, lng) doubles so the formatter doesn't depend
 * on any particular map SDK.
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
        // UTM/MGRS is only defined for 80°S–84°N. Beyond that the NGA library
        // silently CLAMPS to band X/C (a grid up to ~110 km from the true
        // position), so show an explicit out-of-range marker instead.
        outOfRangeMarker(lat)?.let { return it }
        val mgrs = MGRS.from(Point.point(lng, lat))
        val raw = mgrs.coordinate(precision)
        val compact = raw.replace("\\s+".toRegex(), "")
        return if (spaced) compact.withDisplaySpacing() else compact
    }

    /** User-facing UTM grid readout, e.g. `"33N 450000mE 6700000mN"`.
     *  Hemisphere from the latitude sign (UTM N/S); zone + easting + northing
     *  from NGA's `toUTM()`. */
    fun formatUtm(lat: Double, lng: Double): String {
        outOfRangeMarker(lat)?.let { return it }
        val utm = MGRS.from(Point.point(lng, lat)).toUTM()
        val hemi = if (lat >= 0) "N" else "S"
        // Locale.US: default-locale formatting emits non-ASCII digits on
        // Arabic/Farsi/Bengali locales and would diverge from iOS.
        return "%02d%s %.0fmE %.0fmN".format(Locale.US, utm.zone, hemi, utm.easting, utm.northing)
    }

    /** Decode a string like `56H LH 12345 67890` to a (lat, lng).
     *  Returns null on parse failure or on a partial / structurally-invalid
     *  string (so a coarse token can't resolve to a grid-zone corner far away). */
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

    /** Structural MGRS validation, matching iOS `looksLikeMGRS`: zone 1–60,
     *  latitude band C–X (excluding I/O), two 100km-square letters (excluding
     *  I/O), then an optional even easting/northing group. Rejects GZD-only /
     *  partial / invalid-zone-or-band input that NGA would otherwise resolve to a
     *  far grid-zone corner — bringing Android to parity with iOS. */
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
