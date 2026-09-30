package com.tacmap.mgrs

import mil.nga.grid.Hemisphere
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

    /// Label for one grid segment. isVertical is the grid axis (easting
    /// line). Every segment along the same grid line shares lineKey, and
    /// start/end let placeLabels find where the line crosses the screen.
    data class LabelMark(
        val text: String,
        val lat: Double,
        val lng: Double,
        val type: GridType,
        val isVertical: Boolean,
        val lineKey: String,
        val start: Endpoint,
        val end: Endpoint,
    )

    data class ScreenPoint(val x: Float, val y: Float)

    /// A label positioned on screen. runsUpDown is the line's direction on
    /// screen (not the grid axis), so text follows the line on a rotated map.
    data class PlacedLabel(val mark: LabelMark, val x: Float, val y: Float, val runsUpDown: Boolean)

    /// Where grid labels sit. build() hands back a copy of each label for
    /// every 1 km (or 10 / 100 km) segment, and the geometry covers a square
    /// well past the screen edges, so "keep the copy nearest the top/left
    /// margin" usually picked one that was off screen (Android only showed
    /// some because a line's tilt split its copies across dedupe buckets).
    /// Instead each visible line gets one label where it crosses a fixed
    /// column (lines running across the screen) or row (lines running
    /// up/down). 30% in from the left clears the button column and the
    /// crosshair, 70% down sits under the header and side buttons and above
    /// the bottom button. Labels also stay out of the top band (status bar +
    /// MGRS header, ~126 dp) and the bottom band (Centre / Map buttons,
    /// ~95 dp), measured from the screen edge since the map runs edge to
    /// edge. Same numbers as iOS MGRSGridRenderer (dp here, pt there).
    const val LABEL_COLUMN_FRACTION = 0.3f
    const val LABEL_ROW_FRACTION = 0.7f
    const val LABEL_EDGE_MARGIN_DP = 12f
    const val LABEL_TOP_INSET_DP = 140f
    const val LABEL_BOTTOM_INSET_DP = 100f
    /// Where the column meets the row, a column label this close to a row
    /// label gets dropped so the two don't print over each other.
    const val LABEL_CORNER_GAP_DP = 16f

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
                    val label = lineLabel(
                        type,
                        UTM.from(p1, zone.number, zone.hemisphere),
                        UTM.from(p2, zone.number, zone.hemisphere),
                        isVertical,
                    )
                    if (label != null) {
                        labelOut += LabelMark(
                            text = label.first,
                            lat  = midLat,
                            lng  = midLng,
                            type = type,
                            isVertical = isVertical,
                            lineKey = label.second,
                            start = Endpoint(p1.latitude, p1.longitude),
                            end = Endpoint(p2.latitude, p2.longitude),
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
    ///
    /// Also returns a key naming the physical line (zone, axis, metres), so
    /// every segment copy along it groups together, including the same line
    /// drawn as 1 km, 10 km and 100 km.
    private fun lineLabel(gridType: GridType, start: UTM, end: UTM, isVertical: Boolean): Pair<String, String>? {
        val interval = when (gridType) {
            GridType.HUNDRED_KILOMETER -> 100_000L
            GridType.TEN_KILOMETER -> 10_000L
            GridType.KILOMETER -> 1_000L
            else -> return null
        }
        val easting = (start.easting + end.easting) / 2
        val northing = (start.northing + end.northing) / 2
        val index = lineIndex(if (isVertical) easting else northing, interval)
        val hemisphere = if (start.hemisphere == Hemisphere.NORTH) "N" else "S"
        val key = "${start.zone}$hemisphere|${if (isVertical) "E" else "N"}|${index * interval}"
        val text = when (gridType) {
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
        return text to key
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

    /// One label per visible grid line, positioned on screen. Direction is
    /// judged on screen, so a heading-up (rotated) map still gets labels.
    /// width/height are in px; density is px per dp for the insets.
    fun placeLabels(
        labels: List<LabelMark>,
        width: Float,
        height: Float,
        density: Float,
        project: (lat: Double, lng: Double) -> ScreenPoint,
    ): List<PlacedLabel> {
        val side = LABEL_EDGE_MARGIN_DP * density
        val top = LABEL_TOP_INSET_DP * density
        val bottom = height - LABEL_BOTTOM_INSET_DP * density
        val cornerGap = LABEL_CORNER_GAP_DP * density
        if (width <= 2 * side || bottom <= top) return emptyList()
        val column = width * LABEL_COLUMN_FRACTION
        val row = height * LABEL_ROW_FRACTION
        val lines = LinkedHashMap<String, LineLabels>()
        for (mark in labels) {
            val segment = project(mark.start.latitude, mark.start.longitude) to
                project(mark.end.latitude, mark.end.longitude)
            val line = lines.getOrPut(mark.lineKey) { LineLabels(mark) }
            line.segments += segment
            if (labelPriority(mark.type) > labelPriority(line.mark.type)) line.mark = mark
        }
        val placed = lines.values.mapNotNull { line ->
            val hit = crossing(line.segments, column, row) ?: return@mapNotNull null
            if (hit.x < side || hit.x > width - side || hit.y < top || hit.y > bottom) return@mapNotNull null
            PlacedLabel(line.mark, hit.x, hit.y, hit.runsUpDown)
        }
        val rowNearCorner = placed.any { it.runsUpDown && abs(it.x - column) < cornerGap }
        return placed.filter { it.runsUpDown || !rowNearCorner || abs(it.y - row) >= cornerGap }
    }

    // A 100 km line is also a 10 km and a 1 km line. Show its square letter
    // (the digits would just be 0 / 00), otherwise the 1 km value, which
    // already carries the 10 km digit ("50" beats "5").
    private fun labelPriority(type: GridType): Int = when (type) {
        GridType.HUNDRED_KILOMETER -> 3
        GridType.KILOMETER -> 2
        GridType.TEN_KILOMETER -> 1
        else -> 0
    }

    // Where a projected grid line crosses the label column (if it runs across
    // the screen) or the label row (if it runs up/down). null when it doesn't
    // reach it, e.g. a line that stops at a zone edge.
    private fun crossing(
        segments: List<Pair<ScreenPoint, ScreenPoint>>,
        column: Float,
        row: Float,
    ): Crossing? {
        val across = segments.sumOf { (a, b) -> abs(b.x - a.x).toDouble() }
        val upDown = segments.sumOf { (a, b) -> abs(b.y - a.y).toDouble() }
        val runsUpDown = upDown > across
        for ((a, b) in segments) {
            if (runsUpDown) {
                if (a.y == b.y || row < minOf(a.y, b.y) || row > maxOf(a.y, b.y)) continue
                val t = (row - a.y) / (b.y - a.y)
                return Crossing(a.x + t * (b.x - a.x), row, true)
            } else {
                if (a.x == b.x || column < minOf(a.x, b.x) || column > maxOf(a.x, b.x)) continue
                val t = (column - a.x) / (b.x - a.x)
                return Crossing(column, a.y + t * (b.y - a.y), false)
            }
        }
        return null
    }

    private class LineLabels(var mark: LabelMark) {
        val segments = ArrayList<Pair<ScreenPoint, ScreenPoint>>()
    }

    private data class Crossing(val x: Float, val y: Float, val runsUpDown: Boolean)
}
