package com.tacmap.export

import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** Bounded, streaming KML/KMZ importer. No attacker-sized DOM or whole-file string is built. */
object KmlImporter {
    class ImportException(msg: String) : Exception(msg)

    private const val DEFAULT_STROKE = 0xFFFFA000.toInt()
    private const val DEFAULT_FILL = 0x33FFA000
    /** KML has no app-specific persisted pixel-width schema. New external
     * imports use the shared portable 3 dp/pt default; no-density parser calls
     * retain the legacy Android 8 px behavior for compatibility. */
    private const val PORTABLE_DEFAULT_STROKE_WIDTH_DP = 3f
    private const val LEGACY_DEFAULT_STROKE_WIDTH_PX = 8f
    private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 128
    private const val MAX_FEATURES = 10_000
    private const val MAX_COORDINATES = 100_000
    private const val MAX_DEPTH = 64
    private const val MAX_TEXT_CHARS = 4 * 1024 * 1024
    private const val DEADLINE_NANOS = 5_000_000_000L

    fun parseStream(
        input: InputStream,
        existingLayers: List<DrawingLayer>,
        fallbackLayerId: String,
        density: Float? = null,
    ): GeoJsonImporter.Result {
        val storedStrokeWidth = density?.takeIf { it.isFinite() && it > 0f }
            ?.let { PORTABLE_DEFAULT_STROKE_WIDTH_DP * it }
            ?: LEGACY_DEFAULT_STROKE_WIDTH_PX
        val bounded = BufferedInputStream(LimitInputStream(input, MAX_INPUT_BYTES.toLong(), "KML/KMZ exceeds 8 MiB"))
        bounded.mark(4)
        val a = bounded.read()
        val b = bounded.read()
        bounded.reset()
        return if (a == 0x50 && b == 0x4B) parseKmz(bounded, existingLayers, fallbackLayerId, storedStrokeWidth)
        else parseXml(bounded, existingLayers, fallbackLayerId, storedStrokeWidth)
    }

    fun parse(
        kml: String,
        existingLayers: List<DrawingLayer>,
        fallbackLayerId: String,
        density: Float? = null,
    ): GeoJsonImporter.Result {
        val bytes = kml.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_INPUT_BYTES) throw ImportException("KML exceeds 8 MiB")
        val storedStrokeWidth = density?.takeIf { it.isFinite() && it > 0f }
            ?.let { PORTABLE_DEFAULT_STROKE_WIDTH_DP * it }
            ?: LEGACY_DEFAULT_STROKE_WIDTH_PX
        return parseXml(ByteArrayInputStream(bytes), existingLayers, fallbackLayerId, storedStrokeWidth)
    }

    private fun parseKmz(
        input: InputStream,
        existingLayers: List<DrawingLayer>,
        fallbackLayerId: String,
        storedStrokeWidth: Float,
    ): GeoJsonImporter.Result {
        ZipInputStream(input).use { zip ->
            var count = 0
            var inflated = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ZIP_ENTRIES) throw ImportException("KMZ contains too many entries")
                if (entry.size > MAX_INPUT_BYTES ||
                    (entry.size > 0 && entry.compressedSize > 0 && entry.size.toDouble() / entry.compressedSize > 100.0)
                ) throw ImportException("KMZ entry exceeds safe expansion limits")
                val remaining = MAX_INPUT_BYTES.toLong() - inflated
                if (remaining <= 0) throw ImportException("KMZ expands beyond 8 MiB")
                val limitedEntry = LimitInputStream(zip, remaining, "KMZ expands beyond 8 MiB", closeDelegate = false)
                if (!entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)) {
                    return parseXml(limitedEntry, existingLayers, fallbackLayerId, storedStrokeWidth)
                }
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = limitedEntry.read(buffer)
                    if (n < 0) break
                    inflated += n
                }
                zip.closeEntry()
            }
        }
        throw ImportException("No .kml entry inside this KMZ")
    }

    private fun parseXml(
        input: InputStream,
        existingLayers: List<DrawingLayer>,
        fallbackLayerId: String,
        storedStrokeWidth: Float,
    ): GeoJsonImporter.Result {
        val handler = Handler(existingLayers, fallbackLayerId, storedStrokeWidth)
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val reader = factory.newSAXParser().xmlReader
            runCatching { reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.parse(InputSource(input))
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("Not valid KML: ${e.message ?: e.javaClass.simpleName}")
        }
        return handler.result()
    }

    private class Handler(
        existingLayers: List<DrawingLayer>,
        private val fallback: String,
        private val storedStrokeWidth: Float,
    ) : DefaultHandler() {
        private data class Container(val depth: Int, var layerId: String)
        private data class Placemark(
            val depth: Int,
            val layerId: String,
            val caseKey: String,
            val sourceId: String?,
            var name: String = "",
            var notes: String? = null,
            var geometry: String? = null,
            var geometryDepth: Int = -1,
            var coordinates: String? = null
        )

        private val deadline = System.nanoTime() + DEADLINE_NANOS
        private val layersByName = existingLayers.associateBy { it.name }.toMutableMap()
        private val newLayers = mutableListOf<DrawingLayer>()
        private val waypoints = mutableListOf<Waypoint>()
        private val drawings = mutableListOf<DrawingFeature>()
        private val identityOrder = mutableListOf<ParsedExternalImportIdentity>()
        private val layerIdentityOrder = mutableListOf<ParsedExternalImportLayerIdentity>()
        private val containers = ArrayDeque<Container>()
        private var placemark: Placemark? = null
        private var depth = 0
        private var rootSeen = false
        private var features = 0
        private var coordinates = 0
        private var textName: String? = null
        private var textDepth = -1
        private var text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            depth++
            checkBudget()
            if (depth > MAX_DEPTH) throw ImportException("KML nesting is too deep")
            val tag = tag(localName, qName)
            if (!rootSeen) {
                if (tag != "kml") throw ImportException("Not a KML document")
                rootSeen = true
            }
            when (tag) {
                "Document", "Folder" -> containers.addLast(Container(depth, containers.lastOrNull()?.layerId ?: fallback))
                "Placemark" -> {
                    if (++features > MAX_FEATURES) throw ImportException("KML contains too many features")
                    placemark = Placemark(
                        depth = depth,
                        layerId = containers.lastOrNull()?.layerId ?: fallback,
                        caseKey = "placemark-${features - 1}",
                        sourceId = attributes?.getValue("id"),
                    )
                }
                "Point", "LineString", "Polygon" -> placemark?.let {
                    if (it.geometry == null) { it.geometry = tag; it.geometryDepth = depth }
                }
            }
            val p = placemark
            val capture = when {
                tag == "coordinates" && p != null && p.geometry != null && depth > p.geometryDepth -> true
                tag == "name" && p != null && depth == p.depth + 1 -> true
                tag == "description" && p != null && depth == p.depth + 1 -> true
                tag == "name" && containers.lastOrNull()?.depth?.plus(1) == depth -> true
                else -> false
            }
            if (capture) { textName = tag; textDepth = depth; text = StringBuilder() }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (textName == null) return
            if (text.length + length > MAX_TEXT_CHARS) throw ImportException("KML text field is too large")
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = tag(localName, qName)
            if (textName == tag && textDepth == depth) {
                val value = text.toString().trim()
                val p = placemark
                when {
                    tag == "coordinates" && p != null && p.coordinates == null -> p.coordinates = value
                    tag == "name" && p != null && depth == p.depth + 1 -> p.name = value
                    tag == "description" && p != null -> p.notes = value.ifBlank { null }
                    tag == "name" && containers.lastOrNull()?.depth?.plus(1) == depth && value.isNotBlank() ->
                        containers.last().layerId = resolveLayer(value)
                }
                textName = null
                textDepth = -1
                text = StringBuilder()
            }
            when (tag) {
                "Placemark" -> placemark?.let(::finishPlacemark).also { placemark = null }
                "Document", "Folder" -> if (containers.lastOrNull()?.depth == depth) containers.removeLast()
            }
            depth--
        }

        fun result(): GeoJsonImporter.Result {
            if (!rootSeen) throw ImportException("Empty KML document")
            return GeoJsonImporter.Result(
                waypoints = waypoints,
                drawings = drawings,
                newLayers = newLayers,
                identityOrder = identityOrder,
                layerIdentityOrder = layerIdentityOrder,
            )
        }

        private fun resolveLayer(name: String): String {
            layersByName[name]?.let { return it.id }
            val index = newLayers.size
            // Parser-local only. The external import journal replaces this
            // placeholder with a persisted UUID before any store is touched.
            val layer = DrawingLayer(
                id = "__kml_import_layer_$index",
                name = name,
                color = DrawingDocument.FRIENDLY_LAYER_COLOR,
            )
            layersByName[name] = layer
            newLayers += layer
            layerIdentityOrder += ParsedExternalImportLayerIdentity(
                caseKey = "kml-layer-$index",
                indexInNewLayers = index,
            )
            return layer.id
        }

        private fun finishPlacemark(p: Placemark) {
            val raw = p.coordinates ?: return
            val tuples = parseCoordinates(raw)
            if (tuples.isEmpty()) return
            // Preserve the KML Placemark id (including missing/invalid values).
            // The external-only resolver assigns a UUID after parsing.
            val id = p.sourceId.orEmpty()
            when (p.geometry) {
                "Point" -> {
                    val index = waypoints.size
                    waypoints += Waypoint(
                        id = id, name = p.name.ifBlank { "Imported" }, notes = p.notes,
                        latitude = tuples[0].first.latitude, longitude = tuples[0].first.longitude,
                        elevationMetres = tuples[0].second, kind = WaypointKind.Generic,
                        rotation = 0.0, scaleX = 1.0, scaleY = 1.0, layerId = p.layerId
                    )
                    identityOrder += ParsedExternalImportIdentity(
                        caseKey = p.caseKey,
                        kind = ExternalImportObjectKind.WAYPOINT,
                        indexInKind = index,
                        sourceId = p.sourceId,
                    )
                }
                "LineString" -> if (tuples.size >= 2) {
                    val index = drawings.size
                    drawings += drawing(id, p, DrawingGeometry.LINE, tuples.map { it.first })
                    identityOrder += ParsedExternalImportIdentity(
                        caseKey = p.caseKey,
                        kind = ExternalImportObjectKind.DRAWING,
                        indexInKind = index,
                        sourceId = p.sourceId,
                    )
                }
                "Polygon" -> {
                    val pts = tuples.map { it.first }.toMutableList()
                    if (pts.size >= 2 && pts.first() == pts.last()) pts.removeAt(pts.lastIndex)
                    if (pts.size >= 3) {
                        val index = drawings.size
                        drawings += drawing(id, p, DrawingGeometry.POLYGON, pts)
                        identityOrder += ParsedExternalImportIdentity(
                            caseKey = p.caseKey,
                            kind = ExternalImportObjectKind.DRAWING,
                            indexInKind = index,
                            sourceId = p.sourceId,
                        )
                    }
                }
            }
        }

        private fun drawing(id: String, p: Placemark, geometry: DrawingGeometry, points: List<DrawingPoint>) =
            DrawingFeature(id = id, name = p.name, geometry = geometry, points = points, layerId = p.layerId,
                strokeColor = DEFAULT_STROKE, fillColor = DEFAULT_FILL,
                strokeWidth = storedStrokeWidth,
                strokeStyle = DrawingStrokeStyle.SOLID)

        private fun parseCoordinates(raw: String): List<Pair<DrawingPoint, Double?>> {
            val out = ArrayList<Pair<DrawingPoint, Double?>>()
            var start = 0
            while (start < raw.length) {
                while (start < raw.length && raw[start].isWhitespace()) start++
                if (start >= raw.length) break
                var end = start
                while (end < raw.length && !raw[end].isWhitespace()) end++
                if (++coordinates > MAX_COORDINATES) throw ImportException("KML contains too many coordinates")
                val tuple = raw.substring(start, end)
                val parts = tuple.split(',', limit = 4)
                val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: throw ImportException("Invalid KML longitude")
                val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: throw ImportException("Invalid KML latitude")
                val alt = parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                if (!lon.isFinite() || !lat.isFinite() || lon !in -180.0..180.0 || lat !in -90.0..90.0)
                    throw ImportException("KML coordinate is outside WGS84 bounds")
                out += DrawingPoint(latitude = lat, longitude = lon) to alt
                start = end
            }
            return out
        }

        private fun checkBudget() {
            if (System.nanoTime() > deadline) throw ImportException("KML import exceeded time limit")
        }

        private fun tag(localName: String?, qName: String?): String =
            localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfterLast(':')
    }

    private class LimitInputStream(
        delegate: InputStream,
        private val limit: Long,
        private val error: String,
        private val closeDelegate: Boolean = true
    ) : FilterInputStream(delegate) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > limit) throw ImportException(error)
            return value
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val value = super.read(b, off, len)
            if (value > 0 && (count + value).also { count = it } > limit) throw ImportException(error)
            return value
        }
        override fun close() { if (closeDelegate) super.close() }
    }
}
