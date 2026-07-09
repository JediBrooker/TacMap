package com.tacmap.export

import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Import KML / KMZ (Google Earth, ATAK, Caltopo, etc) into TacticalMaps
 * domain objects.
 *
 * Placemark Point -> waypoint; LineString / Polygon (outer ring) ->
 * drawing. KML Folder/Document names map to drawing layers so exported
 * folder structure survives the round-trip. KMZ is just a zip wrapper,
 * we read the first .kml entry (typically doc.kml).
 *
 * Reuses [GeoJsonImporter.Result] so import UI handles GeoJSON and KML
 * identically. Shared inline styles (styleUrl refs) are not resolved
 * in this first cut; imported shapes get the standard amber defaults,
 * same as foreign GeoJSON.
 */
object KmlImporter {

    class ImportException(msg: String) : Exception(msg)

    private const val DEFAULT_STROKE = 0xFFFFA000.toInt()
    private const val DEFAULT_FILL = 0x33FFA000

    /**
     * Parse a KML or KMZ [input] stream. Sniffs zip magic to decide
     * whether to unwrap KMZ first. Caller owns/closes [input].
     */
    fun parseStream(
        input: InputStream,
        existingLayers: List<DrawingLayer>,
        fallbackLayerId: String
    ): GeoJsonImporter.Result {
        val bytes = input.readBytes()
        val text = if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            extractKmlFromKmz(bytes) ?: throw ImportException("No .kml entry inside this KMZ")
        } else {
            bytes.toString(Charsets.UTF_8)
        }
        return parse(text, existingLayers, fallbackLayerId)
    }

    /** Parse raw KML [kml] text. */
    fun parse(
        kml: String,
        existingLayers: List<DrawingLayer>,
        fallbackLayerId: String
    ): GeoJsonImporter.Result {
        val doc = try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }   // KML default namespace, just ignore prefixes
                .newDocumentBuilder()
                .parse(kml.byteInputStream())
        } catch (e: Exception) {
            throw ImportException("Not valid KML: ${e.message}")
        }
        val rootEl = doc.documentElement ?: throw ImportException("Empty KML document")
        if (localName(rootEl.tagName) != "kml" && directChild(rootEl, "Document") == null &&
            directChild(rootEl, "Placemark") == null && directChild(rootEl, "Folder") == null
        ) {
            throw ImportException("Not a KML document")
        }

        val layersByName = existingLayers.associateBy { it.name }.toMutableMap()
        val newLayers = mutableListOf<DrawingLayer>()
        val waypoints = mutableListOf<Waypoint>()
        val drawings = mutableListOf<DrawingFeature>()

        fun resolveLayer(name: String): String {
            layersByName[name]?.let { return it.id }
            val layer = DrawingLayer(
                id = UUID.randomUUID().toString(),
                name = name,
                color = DrawingDocument.FRIENDLY_LAYER_COLOR
            )
            layersByName[name] = layer
            newLayers += layer
            return layer.id
        }

        fun walk(node: Node, layerId: String) {
            val kids = node.childNodes
            for (i in 0 until kids.length) {
                val child = kids.item(i) as? Element ?: continue
                when (localName(child.tagName)) {
                    "Folder", "Document" -> {
                        val name = directChildText(child, "name")
                        val layer = if (!name.isNullOrBlank()) resolveLayer(name) else layerId
                        walk(child, layer)
                    }
                    "Placemark" -> parsePlacemark(child, layerId, waypoints, drawings)
                    else -> walk(child, layerId)   // descend through unknown containers
                }
            }
        }
        walk(rootEl, fallbackLayerId)

        return GeoJsonImporter.Result(
            waypoints = waypoints,
            drawings = drawings,
            newLayers = newLayers
        )
    }

    // ----- Placemark parsing -----

    private fun parsePlacemark(
        placemark: Element,
        layerId: String,
        waypoints: MutableList<Waypoint>,
        drawings: MutableList<DrawingFeature>
    ) {
        val name = directChildText(placemark, "name") ?: ""
        val notes = directChildText(placemark, "description")
        val id = UUID.randomUUID().toString()

        // first recognised geometry wins (MultiGeometry -> first child)
        val geom = firstGeometry(placemark) ?: return
        when (localName(geom.tagName)) {
            "Point" -> {
                val coordsText = descendantText(geom, "coordinates") ?: return
                val pts = parseCoords(coordsText)
                val first = pts.firstOrNull() ?: return
                waypoints += Waypoint(
                    id = id,
                    name = name.ifBlank { "Imported" },
                    notes = notes,
                    latitude = first.latitude,
                    longitude = first.longitude,
                    elevationMetres = firstAltitude(coordsText),
                    kind = WaypointKind.Generic,
                    rotation = 0.0,
                    scaleX = 1.0,
                    scaleY = 1.0,
                    layerId = layerId
                )
            }
            "LineString" -> {
                val pts = parseCoords(descendantText(geom, "coordinates") ?: return)
                if (pts.size < 2) return
                drawings += DrawingFeature(
                    id = id,
                    name = name,
                    geometry = DrawingGeometry.LINE,
                    points = pts,
                    layerId = layerId,
                    strokeColor = DEFAULT_STROKE,
                    fillColor = DEFAULT_FILL,
                    strokeWidth = 8f,
                    strokeStyle = DrawingStrokeStyle.SOLID
                )
            }
            "Polygon" -> {
                // outer ring only, we don't model holes
                val ring = directChild(geom, "outerBoundaryIs")
                    ?.let { directChild(it, "LinearRing") }
                    ?: return
                val pts = parseCoords(descendantText(ring, "coordinates") ?: return).toMutableList()
                if (pts.size >= 2 && pts.first() == pts.last()) pts.removeAt(pts.lastIndex)
                if (pts.size < 3) return
                drawings += DrawingFeature(
                    id = id,
                    name = name,
                    geometry = DrawingGeometry.POLYGON,
                    points = pts,
                    layerId = layerId,
                    strokeColor = DEFAULT_STROKE,
                    fillColor = DEFAULT_FILL,
                    strokeWidth = 8f,
                    strokeStyle = DrawingStrokeStyle.SOLID
                )
            }
        }
    }

    /** First Point/LineString/Polygon under a Placemark (descends into MultiGeometry). */
    private fun firstGeometry(placemark: Element): Element? {
        directChild(placemark, "Point")?.let { return it }
        directChild(placemark, "LineString")?.let { return it }
        directChild(placemark, "Polygon")?.let { return it }
        val multi = directChild(placemark, "MultiGeometry") ?: return null
        val kids = multi.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i) as? Element ?: continue
            when (localName(child.tagName)) {
                "Point", "LineString", "Polygon" -> return child
            }
        }
        return null
    }

    // ----- Coordinate parsing -----

    /** KML coordinates: whitespace-separated lon,lat[,alt] tuples. */
    private fun parseCoords(text: String): List<DrawingPoint> =
        text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(',')
            val lon = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            DrawingPoint(latitude = lat, longitude = lon)
        }

    private fun firstAltitude(text: String): Double? {
        val first = text.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        return first.split(',').getOrNull(2)?.trim()?.toDoubleOrNull()
    }

    // ----- DOM helpers -----

    /** Strip namespace prefix: "kml:Placemark" -> "Placemark" */
    private fun localName(tag: String): String = tag.substringAfterLast(':')

    private fun directChild(parent: Element, tag: String): Element? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k is Element && localName(k.tagName) == tag) return k
        }
        return null
    }

    private fun directChildText(parent: Element, tag: String): String? =
        directChild(parent, tag)?.textContent?.trim()?.ifBlank { null }

    /** First descendant with [tag] (handles coords nested in boundary wrappers). */
    private fun descendantText(parent: Element, tag: String): String? {
        directChild(parent, tag)?.let { return it.textContent?.trim()?.ifBlank { null } }
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i) as? Element ?: continue
            descendantText(child, tag)?.let { return it }
        }
        return null
    }

    // ----- KMZ extraction -----

    private fun extractKmlFromKmz(bytes: ByteArray): String? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var docKml: String? = null
            var firstKml: String? = null
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.endsWith(".kml", ignoreCase = true)) {
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    if (name.equals("doc.kml", ignoreCase = true) ||
                        name.substringAfterLast('/').equals("doc.kml", ignoreCase = true)
                    ) {
                        docKml = content
                    } else if (firstKml == null) {
                        firstKml = content
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            return docKml ?: firstKml
        }
    }
}
