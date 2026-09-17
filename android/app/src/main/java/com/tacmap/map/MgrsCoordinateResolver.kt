package com.tacmap.map

import com.tacmap.mgrs.MgrsFormatter
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.utm.UTM
import java.util.Locale

/** A fully local MGRS move target. [latitude]/[longitude] are the centre of
 * the requested grid square, never the south-west corner returned by NGA. */
internal data class MgrsCoordinateResolution(
    val latitude: Double,
    val longitude: Double,
    val display: String,
    val squareSizeLabel: String,
)

/**
 * Resolves either a full MGRS reference or a numeric grid shorthand at the
 * requested 4/6/8/10-figure precision. Numeric shorthand inherits the grid
 * zone, band, and 100 km square from [referenceLatitude]/[referenceLongitude].
 * This function has no Context, Geocoder, HTTP client, or network fallback.
 */
internal fun resolveMgrsCoordinate(
    raw: String,
    referenceLatitude: Double?,
    referenceLongitude: Double?,
): MgrsCoordinateResolution? {
    val components = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (components.isEmpty()) return null

    val numericOnly = components.all { component -> component.all(Char::isDigit) }
    val compact: String
    val prefix: String
    val digits: String

    if (numericOnly) {
        if (components.size !in 1..2) return null
        if (components.size == 2 && components[0].length != components[1].length) return null
        digits = components.joinToString(separator = "")
        if (digits.length !in SUPPORTED_GRID_DIGITS) return null
        val lat = referenceLatitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lng = referenceLongitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        prefix = extractMgrsPrefix(MgrsFormatter.format(lat, lng, spaced = false)) ?: return null
        compact = prefix + digits
    } else {
        compact = raw.filterNot(Char::isWhitespace).uppercase(Locale.US)
        val match = FULL_SUPPORTED_MGRS.matchEntire(compact) ?: return null
        prefix = match.groupValues[1]
        digits = match.groupValues[2]
    }

    val halfDigits = digits.length / 2
    val centred = centreOfMgrsSquare(compact, halfDigits) ?: return null
    val easting = digits.take(halfDigits)
    val northing = digits.drop(halfDigits)
    return MgrsCoordinateResolution(
        latitude = centred.first,
        longitude = centred.second,
        display = "$prefix $easting $northing",
        squareSizeLabel = squareSizeLabel(halfDigits),
    )
}

internal fun extractMgrsPrefix(mgrs: String): String? {
    val compact = mgrs.uppercase(Locale.US).filterNot(Char::isWhitespace)
    return Regex("""^(\d{1,2}[A-Z][A-Z]{2})""").find(compact)?.groupValues?.getOrNull(1)
        ?: Regex("""^([ABYZ][A-Z]{2})""").find(compact)?.groupValues?.getOrNull(1)
}

internal fun centreOfMgrsSquare(
    compactMgrs: String,
    eastNorthDigits: Int,
): Pair<Double, Double>? {
    val halfMetres = when (eastNorthDigits) {
        2 -> 500.0
        3 -> 50.0
        4 -> 5.0
        5 -> 0.5
        else -> return null
    }
    return runCatching {
        val southwest = MGRS.parse(compactMgrs).toUTM()
        val centre = UTM.create(
            southwest.zone,
            southwest.hemisphere,
            southwest.easting + halfMetres,
            southwest.northing + halfMetres,
        ).toPoint()
        val latitude = centre.latitude
        val rawLongitude = centre.longitude
        if (!latitude.isFinite() || !rawLongitude.isFinite() || latitude !in -90.0..90.0) {
            error("MGRS square centre is outside valid WGS84 bounds")
        }
        val longitude = ((rawLongitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        latitude to longitude
    }.getOrNull()
}

private fun squareSizeLabel(eastNorthDigits: Int): String = when (eastNorthDigits) {
    2 -> "1 km"
    3 -> "100 m"
    4 -> "10 m"
    5 -> "1 m"
    else -> ""
}

private val SUPPORTED_GRID_DIGITS = setOf(4, 6, 8, 10)
private val FULL_SUPPORTED_MGRS = Regex(
    "^((?:(?:0?[1-9]|[1-5]\\d|60)[C-HJ-NP-X]|[ABYZ])[A-HJ-NP-Z]{2})(\\d{4}|\\d{6}|\\d{8}|\\d{10})$"
)
