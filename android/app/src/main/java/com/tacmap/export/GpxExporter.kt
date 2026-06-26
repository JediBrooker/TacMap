package com.tacmap.export

import com.tacmap.models.TrackPoint
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Serialises a recorded track into GPX 1.1 — the universal GPS-exchange format
 * read by Garmin, Strava, Gaia GPS, QGIS, Google Earth, etc. A sibling to
 * [GeoJsonExporter]: still no proprietary lock-in.
 */
object GpxExporter {

    fun export(points: List<TrackPoint>, name: String = "TacMap Track"): String {
        val iso = DateTimeFormatter.ISO_INSTANT
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"TacMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(name)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(p.latitude)
                .append("\" lon=\"").append(p.longitude).append("\">\n")
            p.elevationMetres?.let { sb.append("        <ele>").append(it).append("</ele>\n") }
            sb.append("        <time>").append(iso.format(Instant.ofEpochMilli(p.timeEpochMs))).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
