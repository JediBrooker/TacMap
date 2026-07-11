package com.tacmap.drawings

import android.content.Context
import com.tacmap.util.SafeStore
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

    /** Non-null when on-disk drawings file was unreadable and got quarantined.
     *  Surfaced by UI so user knows their drawings were preserved (not silently
     *  discarded) rather than thinking blank means "no data". */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    fun acknowledgeLoadError() { _loadError.value = null }

    private val undoStack = ArrayDeque<DrawingDocument>()
    private val redoStack = ArrayDeque<DrawingDocument>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    init { load() }

    fun addFeature(feature: DrawingFeature) {
        pushUndo()
        _document.value = _document.value.copy(
            features = _document.value.features + feature
        ).withDefaultLayers()
        persist()
    }

    fun updateFeature(feature: DrawingFeature) {
        pushUndo()
        _document.value = _document.value.copy(
            features = _document.value.features.map {
                if (it.id == feature.id) feature else it
            }
        ).withDefaultLayers()
        persist()
    }

    fun removeFeature(featureId: String) {
        pushUndo()
        _document.value = _document.value.copy(
            features = _document.value.features.filterNot { it.id == featureId }
        ).withDefaultLayers()
        persist()
    }

    fun addLayer(name: String) {
        pushUndo()
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
     * Insert a layer verbatim (preserves supplied id + colour). Used by
     * GeoJSON import so feature.layerId references resolve correctly
     * after round-trip. No-op if layer with same id already exists.
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

    /** Update feature for visual feedback during a gesture (e.g. slider drag)
     *  without pushing to undo stack. Call [updateFeature] at gesture end. */
    fun updateFeatureNoUndo(feature: DrawingFeature) {
        _document.value = _document.value.copy(
            features = _document.value.features.map { if (it.id == feature.id) feature else it }
        ).withDefaultLayers()
        persist()
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_document.value)
        _document.value = snapshot
        persist()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_document.value)
        _document.value = snapshot
        persist()
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun pushUndo() {
        if (undoStack.size >= 50) undoStack.removeFirst()
        undoStack.addLast(_document.value)
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    /** True when the store couldn't be opened b/c the at-rest key is locked.
     *  Blocks persist() so an empty doc never lands on readable-but-locked data. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private fun load() {
        when (val r = SafeStore.readOrQuarantine(file, LABEL) { json.decodeFromString<DrawingDocument>(it) }) {
            is SafeStore.LoadResult.Loaded -> _document.value = r.value.withDefaultLayers()
            is SafeStore.LoadResult.Empty -> Unit // fresh install, keep default document
            is SafeStore.LoadResult.Corrupt ->
                // Do NOT overwrite: unreadable file is preserved as
                // drawings.json.corrupt-* and user is told. Otherwise the next
                // edit would silently persist an empty doc over it.
                _loadError.value = "Saved drawings could not be read and were set aside " +
                    "(${r.quarantinedTo?.name ?: "recovery copy"}). Starting with an empty map."
            is SafeStore.LoadResult.Locked -> {
                _locked.value = true
                _loadError.value = "Drawings are encrypted and locked. ${r.error.message}"
            }
        }
    }

    private fun persist() {
        if (_locked.value) return
        runCatching { SafeStore.writeAtomically(file, LABEL, json.encodeToString(_document.value)) }
            .onFailure { _loadError.value = "Could not save drawings to disk: ${it.message}" }
    }

    private companion object { const val LABEL = "drawings.json" }

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
