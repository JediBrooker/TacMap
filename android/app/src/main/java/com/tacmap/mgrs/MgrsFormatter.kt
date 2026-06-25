package com.tacmap.mgrs

import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType

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

    fun format(lat: Double, lng: Double,
               precision: GridType = defaultPrecision,
               spaced: Boolean = true): String {
        val mgrs = MGRS.from(Point.point(lng, lat))
        val raw = mgrs.coordinate(precision)
        val compact = raw.replace("\\s+".toRegex(), "")
        return if (spaced) compact.withDisplaySpacing() else compact
    }

    /** User-facing UTM grid readout, e.g. `"33N 450000mE 6700000mN"`.
     *  Hemisphere from the latitude sign (UTM N/S); zone + easting + northing
     *  from NGA's `toUTM()`. */
    fun formatUtm(lat: Double, lng: Double): String {
        val utm = MGRS.from(Point.point(lng, lat)).toUTM()
        val hemi = if (lat >= 0) "N" else "S"
        return "%02d%s %.0fmE %.0fmN".format(utm.zone, hemi, utm.easting, utm.northing)
    }

    /** Decode a string like `56H LH 12345 67890` to a (lat, lng).
     *  Returns null on parse failure. */
    fun parse(s: String): Pair<Double, Double>? = try {
        val m = MGRS.parse(s)
        val p = m.toPoint()
        p.latitude to p.longitude
    } catch (_: Throwable) {
        null
    }

    private fun String.withDisplaySpacing(): String {
        val match = Regex("^(\\d{1,2}[A-Z][A-Z]{2})(\\d+)$").matchEntire(this) ?: return this
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        val split = digits.length / 2
        return "$prefix ${digits.take(split)} ${digits.drop(split)}"
    }
}
