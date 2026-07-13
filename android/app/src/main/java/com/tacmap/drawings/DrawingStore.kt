package com.tacmap.drawings

import android.content.Context
import com.tacmap.util.SafeStore
import com.tacmap.models.ModelMutationEvent
import com.tacmap.models.ModelMutationOrigin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DrawingStore private constructor(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "drawings.json"))

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _document = MutableStateFlow(DrawingDocument())
    val document: StateFlow<DrawingDocument> = _document.asStateFlow()
    private val mutationChannel = Channel<ModelMutationEvent>(Channel.UNLIMITED)
    val mutations: Flow<ModelMutationEvent> = mutationChannel.receiveAsFlow()

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

    fun addFeature(feature: DrawingFeature, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        pushUndo()
        _document.value = _document.value.copy(
            features = _document.value.features + feature
        ).withDefaultLayers()
        persist()
        emit(setOf(feature.id), origin)
    }

    /** One undo snapshot and one atomic persistence write for bounded imports. */
    fun addImported(layers: List<DrawingLayer>, features: List<DrawingFeature>, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (layers.isEmpty() && features.isEmpty()) return
        pushUndo()
        val existingIds = _document.value.layers.asSequence().map { it.id }.toHashSet()
        _document.value = _document.value.copy(
            layers = _document.value.layers + layers.filter { existingIds.add(it.id) },
            features = _document.value.features + features
        ).withDefaultLayers()
        persist()
        emit(if (layers.isEmpty()) features.mapTo(HashSet()) { it.id }
            else _document.value.features.mapTo(HashSet()) { it.id }, origin)
    }

    fun updateFeature(feature: DrawingFeature, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_document.value.features.none { it.id == feature.id } ||
            _document.value.features.first { it.id == feature.id } == feature) return
        pushUndo()
        _document.value = _document.value.copy(
            features = _document.value.features.map {
                if (it.id == feature.id) feature else it
            }
        ).withDefaultLayers()
        persist()
        emit(setOf(feature.id), origin)
    }

    fun removeFeature(featureId: String, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_document.value.features.none { it.id == featureId }) return
        pushUndo()
        _document.value = _document.value.copy(
            features = _document.value.features.filterNot { it.id == featureId }
        ).withDefaultLayers()
        persist()
        emit(setOf(featureId), origin)
    }

    fun addLayer(name: String, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        pushUndo()
        val cleanName = name.trim().ifBlank { "Layer ${_document.value.layers.size + 1}" }
        _document.value = _document.value.copy(
            layers = _document.value.layers + DrawingLayer(
                name = cleanName,
                color = nextCustomLayerColor()
            )
        ).withDefaultLayers()
        persist()
        emit(_document.value.features.mapTo(HashSet()) { it.id }, origin)
    }

    /**
     * Insert a layer verbatim (preserves supplied id + colour). Used by
     * GeoJSON import so feature.layerId references resolve correctly
     * after round-trip. No-op if layer with same id already exists.
     */
    fun addLayerVerbatim(layer: DrawingLayer, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_document.value.layers.any { it.id == layer.id }) return
        _document.value = _document.value.copy(
            layers = _document.value.layers + layer
        ).withDefaultLayers()
        persist()
        emit(_document.value.features.mapTo(HashSet()) { it.id }, origin)
    }

    fun setLayerVisible(layerId: String, visible: Boolean, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_document.value.layers.none { it.id == layerId && it.isVisible != visible }) return
        _document.value = _document.value.copy(
            layers = _document.value.layers.map {
                if (it.id == layerId) it.copy(isVisible = visible) else it
            }
        ).withDefaultLayers()
        persist()
        emit(_document.value.features.mapTo(HashSet()) { it.id }, origin)
    }

    /** Update feature for visual feedback during a gesture (e.g. slider drag)
     *  without pushing to undo stack. Call [updateFeature] at gesture end. */
    fun updateFeatureNoUndo(feature: DrawingFeature, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_document.value.features.none { it.id == feature.id } ||
            _document.value.features.first { it.id == feature.id } == feature) return
        _document.value = _document.value.copy(
            features = _document.value.features.map { if (it.id == feature.id) feature else it }
        ).withDefaultLayers()
        persist()
        emit(setOf(feature.id), origin)
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        val changed = changedIds(_document.value, snapshot)
        redoStack.addLast(_document.value)
        _document.value = snapshot
        persist()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        emit(changed, ModelMutationOrigin.LOCAL)
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        val changed = changedIds(_document.value, snapshot)
        undoStack.addLast(_document.value)
        _document.value = snapshot
        persist()
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        emit(changed, ModelMutationOrigin.LOCAL)
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

    private fun emit(ids: Set<String>, origin: ModelMutationOrigin) {
        if (ids.isNotEmpty()) check(mutationChannel.trySend(ModelMutationEvent(ids, origin)).isSuccess)
    }

    private fun changedIds(before: DrawingDocument, after: DrawingDocument): Set<String> {
        val a = before.features.associateBy { it.id }; val b = after.features.associateBy { it.id }
        val changed = (a.keys + b.keys).filterTo(HashSet()) { a[it] != b[it] }
        if (before.layers != after.layers) changed += (a.keys + b.keys)
        return changed
    }

    internal companion object {
        private const val LABEL = "drawings.json"
        fun forTests(filesDir: File) = DrawingStore(File(filesDir, LABEL))
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
