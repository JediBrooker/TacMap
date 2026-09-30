package com.tacmap.mgrs

import mil.nga.grid.features.Bounds
import mil.nga.grid.features.Point
import mil.nga.mgrs.grid.GridType
import mil.nga.mgrs.grid.Grids
import mil.nga.mgrs.gzd.GridZones
import mil.nga.mgrs.utm.UTM
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Generates MGRS grid lines + labels for a map region. Auto-selects
 * detail level (100km/10km/1km) from tile zoom so the grid doesn't
 * drown the map when zoomed out. Labels at midpoint of each line -
 * easting on verticals, northing on horizontals, same as topo sheets.
 */
object MgrsGridRenderer {

    /** Tactical-mode neutral dark-grey for grid lines AND labels. */
    val INK_COLOR: Int = 0xD9303030.toInt()
    val LABEL_TEXT_COLOR: Int = 0xFF282828.toInt()

    /// Grid line endpoint. WGS84 lat/lng, not tied to any map SDK.
    data class Endpoint(val latitude: Double, val longitude: Double)

    data class Segment(
        val start: Endpoint,
        val end: Endpoint,
        val type: GridType
    )

    /// Label on a grid line. isVertical drives rendering orientation.
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

    /** Existing density-scaled width plus exactly one physical display pixel. */
    fun lineWidthPx(type: GridType, density: Float): Float =
        lineWidthDp(type) * density + 1f

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
                        start = Endpoint(p1.latitude, p1.longitude),
                        end   = Endpoint(p2.latitude, p2.longitude),
                        type  = type
                    )

                    // Direction in UTM metres, only place we can cleanly
                    // distinguish easting vs northing axis.
                    val mLine = line.toMeters()
                    val dE = abs(mLine.point1.longitude - mLine.point2.longitude)
                    val dN = abs(mLine.point1.latitude  - mLine.point2.latitude)
                    val isVertical = dE < dN

                    val midLat = (p1.latitude  + p2.latitude)  / 2.0
                    val midLng = (p1.longitude + p2.longitude) / 2.0
                    // Label value comes from the endpoints in this zone's UTM,
                    // they sit on the line to ~1 cm. The lat/lon midpoint sags
                    // off it (1.5 m on a 10 km line, ~150 m on 100 km) and
                    // MGRS.from() truncates, so the 87000 line read "86".
                    val text = lineLabelText(
                        type,
                        UTM.from(p1, zone.number, zone.hemisphere),
                        UTM.from(p2, zone.number, zone.hemisphere),
                        isVertical,
                    )
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

    /// Format easting/northing for a grid line. 1km = 2-digit ("20"),
    /// 10km = single digit, 100km = column/row letter of the square east of
    /// a vertical line / north of a horizontal one (same side the numbers
    /// count from, line 87 is the bottom edge of square 87).
    private fun lineLabelText(gridType: GridType, start: UTM, end: UTM, isVertical: Boolean): String {
        val interval = when (gridType) {
            GridType.HUNDRED_KILOMETER -> 100_000L
            GridType.TEN_KILOMETER -> 10_000L
            GridType.KILOMETER -> 1_000L
            else -> return ""
        }
        val easting = (start.easting + end.easting) / 2
        val northing = (start.northing + end.northing) / 2
        val index = lineIndex(if (isVertical) easting else northing, interval)
        return when (gridType) {
            GridType.HUNDRED_KILOMETER -> {
                val inSquare = (index * interval + interval / 2).toDouble()
                val square = if (isVertical) {
                    UTM(start.zone, start.hemisphere, inSquare, northing)
                } else {
                    UTM(start.zone, start.hemisphere, easting, inSquare)
                }.toMGRS()
                if (isVertical) square.column.toString() else square.row.toString()
            }
            GridType.TEN_KILOMETER -> (index % 10).toString()
            else -> "%02d".format(index % 100)
        }
    }

    // Which grid line this is, in units of the interval. The endpoints went
    // UTM -> lat/lon -> UTM and land a hair either side of the line, so snap
    // when we're within a metre. Anything that isn't on the interval at all
    // (e.g. a clipped zone edge) keeps the old floor, i.e. the square it's in.
    private fun lineIndex(value: Double, interval: Long): Long {
        val nearest = Math.round(value / interval)
        return if (abs(value - nearest * interval) < LINE_SNAP_METRES) {
            nearest
        } else {
            floor(value / interval).toLong()
        }
    }

    private const val LINE_SNAP_METRES = 1.0
}
