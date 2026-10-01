package com.tacmap.calibration.fiducial

import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatum
import com.tacmap.calibration.GeodeticPoint
import com.tacmap.calibration.PagePoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** [Grid intersection | Feature] segment, contract s4.5 */
@Serializable
enum class CalibrationPointKind(val code: String) {
    @SerialName("intersection") INTERSECTION("intersection"),
    @SerialName("feature") FEATURE("feature");

    companion object {
        fun fromCode(code: String?): CalibrationPointKind? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
enum class GridSource(val code: String) {
    @SerialName("mgrs") MGRS("mgrs"),
    @SerialName("utm") UTM("utm"),
}

/**
 * What the user typed, already parsed. Grid E/N is the SW corner of the cell on
 * the SHEET datum; geographic is sheet datum unless the point carries the WGS84
 * override (GPS fix, migrated v1 points).
 */
@Serializable
sealed class CalibrationReference {
    @Serializable
    @SerialName("grid")
    data class Grid(
        val zone: Int,
        val south: Boolean,
        val easting: Double,
        val northing: Double,
        val cellSizeM: Double = 1.0,
        val source: GridSource = GridSource.MGRS,
    ) : CalibrationReference()

    @Serializable
    @SerialName("geographic")
    data class Geographic(val lat: Double, val lon: Double) : CalibrationReference()
}

/** a key + its named args, the UI turns it into text (keeps the pure layer locale free) */
data class StatusMessage(val key: String, val args: Map<String, Any> = emptyMap())

@Serializable
data class StoredPagePoint(val x: Double, val y: Double) {
    val page: PagePoint get() = PagePoint(x, y)

    companion object {
        fun of(p: PagePoint) = StoredPagePoint(p.x, p.y)
    }
}

/**
 * One calibration point. [number] is stable for the whole session (never
 * renumbered, a delete leaves a gap), [page] is raw PDF user space captured
 * through the georef that was on screen.
 */
@Serializable
data class CalibrationPoint(
    val id: String,
    val number: Int,
    val page: StoredPagePoint,
    /** what was typed, shown back in the list. not re-parsed on load */
    val input: String,
    val reference: CalibrationReference,
    val kind: CalibrationPointKind = CalibrationPointKind.INTERSECTION,
    /** "WGS84" for GPS / migrated points, null = sheet datum */
    val datumOverride: String? = null,
    val label: String? = null,
    /** the canonical reading, for the points sheet */
    val canonical: String? = null,
) {
    val pagePoint: PagePoint get() = page.page

    val isGrid: Boolean get() = reference is CalibrationReference.Grid
}

/** contract s5: sheet datum lat/lon, plus the grid E/N it was typed in (kind offset applied) */
data class ResolvedReference(
    val latitude: Double,
    val longitude: Double,
    val grid: GridPosition?,
) {
    data class GridPosition(val zone: Int, val south: Boolean, val easting: Double, val northing: Double)
}

const val WGS84_OVERRIDE = "WGS84"

fun CalibrationPoint.resolved(sheetDatum: GeoDatum): ResolvedReference? = when (val r = reference) {
    is CalibrationReference.Grid -> {
        // feature = cell centre, only for real cells (10 figures is already a 1 m point)
        val off = if (kind == CalibrationPointKind.FEATURE && r.cellSizeM > 1.0) r.cellSizeM / 2.0 else 0.0
        val e = r.easting + off
        val n = r.northing + off
        if (r.zone !in 1..60) {
            null
        } else {
            GeoCrs.utm(r.zone, r.south).inverse(e, n, sheetDatum.ellipsoid)?.let {
                ResolvedReference(it.latitude, it.longitude, ResolvedReference.GridPosition(r.zone, r.south, e, n))
            }
        }
    }
    is CalibrationReference.Geographic -> {
        val local: GeodeticPoint? = if (datumOverride == WGS84_OVERRIDE) {
            sheetDatum.fromWGS84(r.lat, r.lon)
        } else {
            GeodeticPoint(r.lat, r.lon).takeIf { it.isValid() }
        }
        local?.let { ResolvedReference(it.latitude, it.longitude, null) }
    }
}
