package com.tacticalmaps.mgrs

import mil.nga.grid.features.Bounds
import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import mil.nga.mgrs.grid.Grids
import mil.nga.mgrs.gzd.GridZones
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Generates MGRS grid line polylines + per-line grid-square labels
 * covering a map region. Detail (100km → 10km → 1km) is auto-selected
 * from the current tile zoom so the overlay doesn't drown the map
 * when zoomed out.
 *
 * Labels are placed at the midpoint of each grid line — easting digits
 * on vertical (north-south) lines, northing digits on horizontal
 * (east-west) lines — matching the convention on 1:50,000 topo sheets.
 */
object MgrsGridRenderer {

    /** Tactical-mode neutral dark-grey for grid lines AND labels. */
    val INK_COLOR: Int = 0xD9303030.toInt()
    val LABEL_TEXT_COLOR: Int = 0xFF282828.toInt()

    data class Segment(
        val start: GeoPoint,
        val end: GeoPoint,
        val type: GridType
    )

    /// One axis-specific label centred on a grid line. `isVertical`
    /// drives the rendering orientation.
    data class LabelMark(
        val text: String,
        val lat: Double,
        val lng: Double,
        val type: GridType,
        val isVertical: Boolean
    )

    fun lineWidthDp(type: GridType): Float = when (type) {
        GridType.HUNDRED_KILOMETER -> 2.0f
        GridType.TEN_KILOMETER     -> 1.3f
        GridType.KILOMETER         -> 0.8f
        else                       -> 0.6f
    }

    fun labelTextSp(type: GridType): Float = when (type) {
        GridType.HUNDRED_KILOMETER -> 14f
        GridType.TEN_KILOMETER     -> 12f
        GridType.KILOMETER         -> 11f
        else                       -> 9f
    }

    fun build(
        minLat: Double, minLng: Double,
        maxLat: Double, maxLng: Double,
        mapWidthPx: Int
    ): Pair<List<Segment>, List<LabelMark>> {
        if (minLat >= maxLat || minLng >= maxLng) return emptyList<Segment>() to emptyList()

        val degreesPerPx = (maxLng - minLng) / mapWidthPx.coerceAtLeast(1)
        val zoom = log2(360.0 / (256.0 * degreesPerPx))
            .roundToInt()
            .coerceIn(0, 20)

        val types = buildList {
            add(GridType.HUNDRED_KILOMETER)
            if (zoom >= 8)  add(GridType.TEN_KILOMETER)
            if (zoom >= 12) add(GridType.KILOMETER)
        }

        val bounds = Bounds.degrees(minLng, minLat, maxLng, maxLat)
        val zones = GridZones.getZones(bounds)
        val grids = Grids()
        val segOut = ArrayList<Segment>(256)
        val labelOut = ArrayList<LabelMark>(128)
        for (type in types) {
            val grid = grids.getGrid(type) ?: continue
            for (zone in zones) {
                val lines = grid.getLines(bounds, zone) ?: continue
                for (line in lines) {
                    val degLine = line.toDegrees()
                    val p1 = degLine.point1
                    val p2 = degLine.point2
                    segOut += Segment(
                        start = GeoPoint(p1.latitude, p1.longitude),
                        end   = GeoPoint(p2.latitude, p2.longitude),
                        type  = type
                    )

                    // Direction in UTM metres — only here can we tell
                    // easting-axis vs northing-axis cleanly.
                    val mLine = line.toMeters()
                    val dE = abs(mLine.point1.longitude - mLine.point2.longitude)
                    val dN = abs(mLine.point1.latitude  - mLine.point2.latitude)
                    val isVertical = dE < dN

                    val midLat = (p1.latitude  + p2.latitude)  / 2.0
                    val midLng = (p1.longitude + p2.longitude) / 2.0
                    val mgrs = MGRS.from(Point.point(midLng, midLat))
                    val text = lineLabelText(type, mgrs, isVertical)
                    if (text.isNotEmpty()) {
                        labelOut += LabelMark(
                            text = text,
                            lat  = midLat,
                            lng  = midLng,
                            type = type,
                            isVertical = isVertical
                        )
                    }
                }
            }
        }
        return segOut to labelOut
    }

    /// Format the easting/northing value for one grid line. 1km lines
    /// get 2-digit numbers (e.g. "20"); 10km lines get a single digit;
    /// 100km lines get the column or row letter so the intersection
    /// reads as the full square ID.
    private fun lineLabelText(gridType: GridType, mgrs: MGRS, isVertical: Boolean): String {
        return when (gridType) {
            GridType.HUNDRED_KILOMETER ->
                if (isVertical) mgrs.column.toString() else mgrs.row.toString()
            GridType.TEN_KILOMETER -> {
                val value = if (isVertical) mgrs.easting else mgrs.northing
                ((value / 10_000) % 10).toString()
            }
            GridType.KILOMETER -> {
                val value = if (isVertical) mgrs.easting else mgrs.northing
                "%02d".format((value / 1_000) % 100)
            }
            else -> ""
        }
    }
}
