package com.tacticalmaps.drawings

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
)

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
