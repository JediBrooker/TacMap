package com.tacticalmaps.drawings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DrawingStore(context: Context) {

    private val file = File(context.filesDir, "drawings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _document = MutableStateFlow(DrawingDocument())
    val document: StateFlow<DrawingDocument> = _document.asStateFlow()

    init { load() }

    fun addFeature(feature: DrawingFeature) {
        _document.value = _document.value.copy(
            features = _document.value.features + feature
        ).withDefaultLayers()
        persist()
    }

    fun updateFeature(feature: DrawingFeature) {
        _document.value = _document.value.copy(
            features = _document.value.features.map {
                if (it.id == feature.id) feature else it
            }
        ).withDefaultLayers()
        persist()
    }

    fun removeFeature(featureId: String) {
        _document.value = _document.value.copy(
            features = _document.value.features.filterNot { it.id == featureId }
        ).withDefaultLayers()
        persist()
    }

    fun addLayer(name: String) {
        val cleanName = name.trim().ifBlank { "Layer ${_document.value.layers.size + 1}" }
        _document.value = _document.value.copy(
            layers = _document.value.layers + DrawingLayer(
                name = cleanName,
                color = nextCustomLayerColor()
            )
        ).withDefaultLayers()
        persist()
    }

    /**
     * Insert a layer verbatim (preserves the supplied id + colour). Used
     * by GeoJSON import so feature.layerId references resolve correctly
     * after a round-trip. If a layer with the same id already exists,
     * this is a no-op.
     */
    fun addLayerVerbatim(layer: DrawingLayer) {
        if (_document.value.layers.any { it.id == layer.id }) return
        _document.value = _document.value.copy(
            layers = _document.value.layers + layer
        ).withDefaultLayers()
        persist()
    }

    fun setLayerVisible(layerId: String, visible: Boolean) {
        _document.value = _document.value.copy(
            layers = _document.value.layers.map {
                if (it.id == layerId) it.copy(isVisible = visible) else it
            }
        ).withDefaultLayers()
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching { json.decodeFromString<DrawingDocument>(file.readText()) }
            .onSuccess { _document.value = it.withDefaultLayers() }
    }

    private fun persist() {
        runCatching { file.writeText(json.encodeToString(_document.value)) }
    }

    private fun DrawingDocument.withDefaultLayers(): DrawingDocument {
        val existingById = layers.associateBy { it.id }
        val defaults = DrawingDocument.defaultLayers().map { defaultLayer ->
            existingById[defaultLayer.id]?.copy(
                name = defaultLayer.name,
                color = defaultLayer.color
            ) ?: defaultLayer
        }
        val customLayers = layers.filterNot { it.id in DrawingDocument.DEFAULT_LAYER_IDS }
        return copy(layers = defaults + customLayers)
    }

    private fun nextCustomLayerColor(): Int {
        val customLayerCount = _document.value.layers.count {
            it.id !in DrawingDocument.DEFAULT_LAYER_IDS
        }
        return DrawingDocument.CUSTOM_LAYER_COLORS[
            customLayerCount % DrawingDocument.CUSTOM_LAYER_COLORS.size
        ]
    }
}
