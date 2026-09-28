package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.waypoints.SymbolListAffiliation
import com.tacmap.waypoints.Waypoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** A KMZ-relative symbol image and its anchor as KML hotSpot fractions (y from the bottom). */
data class KmlIcon(
    val path: String,
    val hotSpotX: Double = 0.5,
    val hotSpotY: Double = 0.5,
)

/**
 * KML export of mission objects for Google Earth, ATAK and GIS tools. Layers
 * become folders (hidden layers export with visibility 0), symbols and
 * drawings become styled placemarks, and each symbol carries its MGRS grid in
 * ExtendedData. Placemark ids are `tacmap-` plus the object id (valid XML IDs
 * cannot start with a digit). iOS mirrors this in `KMLExporter.swift`;
 * `testdata/kml_export.json` pins the exact KML text on both platforms.
 */
object KmlExporter {
    /**
     * @param icons symbol ID to KMZ image; symbols without one use an
     *   affiliation-coloured default pin.
     * @param density display density, to convert stored stroke widths (pixels)
     *   to the density-independent width iOS and the GeoJSON export use.
     */
    fun export(
        waypoints: List<Waypoint>,
        drawings: List<DrawingFeature>,
        layers: List<DrawingLayer>,
        icons: Map<String, KmlIcon> = emptyMap(),
        density: Float = 1f,
    ): String {
        val writer = Writer(icons, density)
        val layerIds = layers.mapTo(HashSet()) { it.id }
        val body = mutableListOf<String>()
        for (layer in layers) {
            val placemarks = mutableListOf<String>()
            waypoints.filter { it.layerId == layer.id }.forEach { placemarks += writer.placemark(it, 6) }
            drawings.filter { it.layerId == layer.id }.forEach { placemarks += writer.placemark(it, 6) }
            if (placemarks.isEmpty()) continue
            body += "    <Folder>"
            body += "      <name>${escape(layer.name)}</name>"
            if (!layer.isVisible) body += "      <visibility>0</visibility>"
            body += placemarks
            body += "    </Folder>"
        }
        waypoints.filter { it.layerId !in layerIds }.forEach { body += writer.placemark(it, 4) }
        drawings.filter { it.layerId !in layerIds }.forEach { body += writer.placemark(it, 4) }

        val lines = mutableListOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<kml xmlns="http://www.opengis.net/kml/2.2">""",
            "  <Document>",
            "    <name>TacMap</name>",
        )
        for (style in writer.styles) {
            lines += """    <Style id="${style.id}">"""
            style.lines.forEach { lines += "      $it" }
            lines += "    </Style>"
        }
        lines += body
        lines += listOf("  </Document>", "</kml>")
        return lines.joinToString("\n") + "\n"
    }

    /** KML `aabbggrr` colour for the RGB part of an ARGB int. */
    fun kmlColor(argb: Int, alpha: Int): String {
        val rgb = argb and 0xFFFFFF
        val red = rgb shr 16 and 0xFF
        val green = rgb shr 8 and 0xFF
        val blue = rgb and 0xFF
        return String.format(Locale.ROOT, "%02x%02x%02x%02x", alpha.coerceIn(0, 255), blue, green, red)
    }

    /** XML-escapes text and drops control characters XML 1.0 does not allow. */
    fun escape(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when (codePoint) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                '"'.code -> append("&quot;")
                '\''.code -> append("&apos;")
                '\t'.code, '\n'.code, '\r'.code -> appendCodePoint(codePoint)
                else -> if (codePoint >= 0x20) appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
    }

    fun coordinate(latitude: Double, longitude: Double, altitude: Double? = null): String {
        var text = fixed(longitude, 7) + "," + fixed(latitude, 7)
        if (altitude != null && altitude.isFinite()) text += "," + fixed(altitude, 1)
        return text
    }

    /**
     * Locale-independent fixed-point text rounded like C's `%.Nf` (the exact
     * binary value, ties to even), so it matches iOS digit for digit.
     */
    fun fixed(value: Double, decimals: Int): String {
        if (!value.isFinite()) return "0." + "0".repeat(decimals)
        return BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
    }

    private fun fraction(value: Double): String =
        BigDecimal(value.coerceIn(0.0, 1.0)).setScale(3, RoundingMode.HALF_EVEN)
            .stripTrailingZeros().toPlainString()

    private fun coordinateList(points: List<DrawingPoint>): String =
        points.joinToString(" ") { coordinate(it.latitude, it.longitude) }

    private fun affiliationColor(affiliation: SymbolListAffiliation): String = when (affiliation) {
        SymbolListAffiliation.FRIEND -> "ffd85f0e"
        SymbolListAffiliation.HOSTILE -> "ff1f28d8"
        SymbolListAffiliation.NEUTRAL -> "ff348a1e"
        SymbolListAffiliation.UNKNOWN -> "ff00a4e2"
        SymbolListAffiliation.OTHER -> "ffffffff"
    }

    private class Style(val id: String, val lines: List<String>)

    private class Writer(private val icons: Map<String, KmlIcon>, private val density: Float) {
        val styles = mutableListOf<Style>()
        private val styleIds = HashSet<String>()

        fun use(id: String, lines: List<String>): String {
            if (styleIds.add(id)) styles += Style(id, lines)
            return id
        }

        fun placemark(waypoint: Waypoint, indent: Int): List<String> {
            val icon = icons[waypoint.id]
            val styleId = if (icon != null) {
                val stem = icon.path.substringAfterLast('/').removeSuffix(".png")
                use("icon-$stem", listOf(
                    "<IconStyle>",
                    "  <Icon>",
                    "    <href>${escape(icon.path)}</href>",
                    "  </Icon>",
                    """  <hotSpot x="${fraction(icon.hotSpotX)}" y="${fraction(icon.hotSpotY)}" xunits="fraction" yunits="fraction"/>""",
                    "</IconStyle>",
                ))
            } else {
                val affiliation = SymbolListAffiliation.of(waypoint)
                use("sym-${affiliation.name.lowercase()}", listOf(
                    "<IconStyle>",
                    "  <color>${affiliationColor(affiliation)}</color>",
                    "</IconStyle>",
                ))
            }
            val lines = mutableListOf(
                """<Placemark id="tacmap-${escape(waypoint.id.lowercase())}">""",
                "  <name>${escape(waypoint.name)}</name>",
            )
            waypoint.notes?.takeIf { it.isNotBlank() }?.let { lines += "  <description>${escape(it)}</description>" }
            val grid = MgrsFormatter.format(waypoint.latitude, waypoint.longitude, spaced = true)
            lines += listOf(
                "  <styleUrl>#$styleId</styleUrl>",
                "  <ExtendedData>",
                """    <Data name="MGRS">""",
                "      <value>${escape(grid)}</value>",
                "    </Data>",
                "  </ExtendedData>",
                "  <Point>",
                "    <coordinates>${coordinate(waypoint.latitude, waypoint.longitude, waypoint.elevationMetres)}</coordinates>",
                "  </Point>",
                "</Placemark>",
            )
            val pad = " ".repeat(indent)
            return lines.map { pad + it }
        }

        fun placemark(feature: DrawingFeature, indent: Int): List<String> {
            val points = feature.effectivePoints
            val stroke = kmlColor(feature.strokeColor, 255)
            val portableWidth = if (density > 0f) feature.strokeWidth / density else feature.strokeWidth
            val width = fixed(portableWidth.toDouble(), 1)
            val styleId: String
            val geometry: List<String>
            when (feature.geometry) {
                DrawingGeometry.POINT -> {
                    val point = points.firstOrNull() ?: return emptyList()
                    styleId = use("point-$stroke", listOf("<IconStyle>", "  <color>$stroke</color>", "</IconStyle>"))
                    geometry = listOf(
                        "<Point>",
                        "  <coordinates>${coordinate(point.latitude, point.longitude)}</coordinates>",
                        "</Point>",
                    )
                }
                DrawingGeometry.LINE -> {
                    if (points.size < 2) return emptyList()
                    styleId = use("line-$stroke-$width", listOf(
                        "<LineStyle>", "  <color>$stroke</color>", "  <width>$width</width>", "</LineStyle>",
                    ))
                    geometry = listOf(
                        "<LineString>",
                        "  <tessellate>1</tessellate>",
                        "  <coordinates>${coordinateList(points)}</coordinates>",
                        "</LineString>",
                    )
                }
                DrawingGeometry.POLYGON -> {
                    if (points.size < 3) return emptyList()
                    val fill = kmlColor(feature.fillColor, feature.fillColor ushr 24 and 0xFF)
                    styleId = use("poly-$stroke-$width-$fill", listOf(
                        "<LineStyle>", "  <color>$stroke</color>", "  <width>$width</width>", "</LineStyle>",
                        "<PolyStyle>", "  <color>$fill</color>", "</PolyStyle>",
                    ))
                    val ring = if (points.first() == points.last()) points else points + points.first()
                    geometry = listOf(
                        "<Polygon>",
                        "  <tessellate>1</tessellate>",
                        "  <outerBoundaryIs>",
                        "    <LinearRing>",
                        "      <coordinates>${coordinateList(ring)}</coordinates>",
                        "    </LinearRing>",
                        "  </outerBoundaryIs>",
                        "</Polygon>",
                    )
                }
            }
            val lines = mutableListOf("""<Placemark id="tacmap-${escape(feature.id.lowercase())}">""")
            feature.name.takeIf { it.isNotBlank() }?.let { lines += "  <name>${escape(it)}</name>" }
            feature.notes?.takeIf { it.isNotBlank() }?.let { lines += "  <description>${escape(it)}</description>" }
            lines += "  <styleUrl>#$styleId</styleUrl>"
            geometry.forEach { lines += "  $it" }
            lines += "</Placemark>"
            val pad = " ".repeat(indent)
            return lines.map { pad + it }
        }
    }
}
