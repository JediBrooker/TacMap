package com.tacticalmaps.drawings

import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DrawingPoint(
    val latitude: Double,
    val longitude: Double
)

@Serializable
enum class DrawingGeometry(val displayName: String) {
    @SerialName("point") POINT("Point"),
    @SerialName("line") LINE("Line"),
    @SerialName("polygon") POLYGON("Polygon")
}

@Serializable
enum class DrawingStrokeStyle(val displayName: String) {
    @SerialName("solid") SOLID("Solid"),
    @SerialName("dashed") DASHED("Dashed")
}

@Serializable
data class DrawingLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int = DrawingDocument.FRIENDLY_LAYER_COLOR,
    @SerialName("is_visible") val isVisible: Boolean = true,
    @SerialName("created_at_epoch_ms") val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class DrawingFeature(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val geometry: DrawingGeometry,
    val points: List<DrawingPoint>,
    @SerialName("layer_id") val layerId: String = DrawingDocument.DEFAULT_LAYER_ID,
    @SerialName("stroke_color") val strokeColor: Int = 0xFFFFA000.toInt(),
    @SerialName("fill_color") val fillColor: Int = 0x33FFA000,
    @SerialName("stroke_width") val strokeWidth: Float = 8f,
    @SerialName("stroke_style") val strokeStyle: DrawingStrokeStyle = DrawingStrokeStyle.SOLID,
    @SerialName("scale_x") val scaleX: Double = 1.0,
    @SerialName("scale_y") val scaleY: Double = 1.0,
    @SerialName("rotation_degrees") val rotationDegrees: Double = 0.0,
    @SerialName("created_at_epoch_ms") val createdAt: Long = System.currentTimeMillis()
) {
    /// Coordinates with rotation + scale applied around the centroid.
    /// Vertex-edit handles render against these so the dots line up
    /// with the rendered polyline / polygon.
    val effectivePoints: List<DrawingPoint>
        get() {
            if (points.size < 2 && geometry != DrawingGeometry.POINT) return points
            if (scaleX == 1.0 && scaleY == 1.0 && rotationDegrees == 0.0) return points
            val centerLat = points.map { it.latitude }.average()
            val centerLng = points.map { it.longitude }.average()
            val lonScale = cos(Math.toRadians(centerLat)).coerceAtLeast(0.000001)
            val radians = Math.toRadians(-rotationDegrees)
            val cosA = cos(radians)
            val sinA = sin(radians)
            return points.map { p ->
                val localX = (p.longitude - centerLng) * lonScale
                val localY = p.latitude - centerLat
                val sx = localX * scaleX
                val sy = localY * scaleY
                val rx = sx * cosA - sy * sinA
                val ry = sx * sinA + sy * cosA
                DrawingPoint(
                    latitude  = centerLat + ry,
                    longitude = centerLng + rx / lonScale
                )
            }
        }

    /// Bake any pending rotation/scale into `points` and reset the
    /// transform. Called automatically by the vertex-edit helpers so
    /// the user's dragged handle position matches what gets persisted.
    fun bakedTransform(): DrawingFeature {
        if (scaleX == 1.0 && scaleY == 1.0 && rotationDegrees == 0.0) return this
        return copy(
            points = effectivePoints,
            scaleX = 1.0,
            scaleY = 1.0,
            rotationDegrees = 0.0
        )
    }

    /// Move the vertex at `index` to a new coordinate. Returns the
    /// updated feature (with any transform baked) or the receiver
    /// unchanged if the index is out of range.
    fun withVertexMoved(index: Int, lat: Double, lng: Double): DrawingFeature {
        val baked = bakedTransform()
        if (index < 0 || index >= baked.points.size) return baked
        val updated = baked.points.toMutableList().also {
            it[index] = DrawingPoint(latitude = lat, longitude = lng)
        }
        return baked.copy(points = updated)
    }

    /// Insert a new vertex at `index`, shifting later points right.
    /// Used when a midpoint handle is dragged or tapped.
    fun withVertexInserted(index: Int, lat: Double, lng: Double): DrawingFeature {
        val baked = bakedTransform()
        if (index < 0 || index > baked.points.size) return baked
        val updated = baked.points.toMutableList().also {
            it.add(index, DrawingPoint(latitude = lat, longitude = lng))
        }
        return baked.copy(points = updated)
    }

    /// Remove the vertex at `index`. Returns null when removing would
    /// drop the feature below its kind's minimum vertex count.
    fun withVertexRemovedOrNull(index: Int): DrawingFeature? {
        val baked = bakedTransform()
        val minCount = when (baked.geometry) {
            DrawingGeometry.POINT -> 1
            DrawingGeometry.LINE -> 2
            DrawingGeometry.POLYGON -> 3
        }
        if (baked.points.size <= minCount) return null
        if (index < 0 || index >= baked.points.size) return null
        return baked.copy(
            points = baked.points.toMutableList().also { it.removeAt(index) }
        )
    }

    /// Anchor point for a name-label on the map. Centroid for polygons,
    /// mid-segment for polylines, the single coordinate for points.
    /// Returns null if the feature has no usable coordinates.
    val labelAnchor: DrawingPoint?
        get() {
            if (points.isEmpty()) return null
            return when (geometry) {
                DrawingGeometry.POINT -> points.first()
                DrawingGeometry.LINE -> {
                    if (points.size < 2) return null
                    val mid = points.size / 2
                    val a = points[mid - 1]
                    val b = points[mid]
                    DrawingPoint(
                        latitude  = (a.latitude  + b.latitude ) / 2.0,
                        longitude = (a.longitude + b.longitude) / 2.0
                    )
                }
                DrawingGeometry.POLYGON -> {
                    val lat = points.sumOf { it.latitude  } / points.size
                    val lon = points.sumOf { it.longitude } / points.size
                    DrawingPoint(latitude = lat, longitude = lon)
                }
            }
        }
}

@Serializable
data class DrawingDocument(
    val layers: List<DrawingLayer> = defaultLayers(),
    val features: List<DrawingFeature> = emptyList()
) {
    companion object {
        const val DEFAULT_LAYER_ID = "default"
        const val HOSTILE_LAYER_ID = "hostile"
        const val UNKNOWN_LAYER_ID = "unknown"
        const val CIVILIAN_LAYER_ID = "civilian"

        val FRIENDLY_LAYER_COLOR: Int = 0xFF1E88E5.toInt()
        val HOSTILE_LAYER_COLOR: Int = 0xFFE53935.toInt()
        val UNKNOWN_LAYER_COLOR: Int = 0xFFFFC107.toInt()
        val CIVILIAN_LAYER_COLOR: Int = 0xFF43A047.toInt()

        val CUSTOM_LAYER_COLORS: List<Int> = listOf(
            0xFFFFA000.toInt(),
            0xFF8E24AA.toInt(),
            0xFF00ACC1.toInt(),
            0xFF6D4C41.toInt(),
            0xFF546E7A.toInt()
        )

        val DEFAULT_LAYER_IDS: Set<String> = setOf(
            DEFAULT_LAYER_ID,
            HOSTILE_LAYER_ID,
            UNKNOWN_LAYER_ID,
            CIVILIAN_LAYER_ID
        )

        fun defaultLayer(): DrawingLayer = DrawingLayer(
            id = DEFAULT_LAYER_ID,
            name = "Friendly",
            color = FRIENDLY_LAYER_COLOR
        )

        fun defaultLayers(): List<DrawingLayer> = listOf(
            defaultLayer(),
            DrawingLayer(
                id = HOSTILE_LAYER_ID,
                name = "Hostile",
                color = HOSTILE_LAYER_COLOR
            ),
            DrawingLayer(
                id = UNKNOWN_LAYER_ID,
                name = "Unknown",
                color = UNKNOWN_LAYER_COLOR
            ),
            DrawingLayer(
                id = CIVILIAN_LAYER_ID,
                name = "Civilian",
                color = CIVILIAN_LAYER_COLOR
            )
        )
    }
}
