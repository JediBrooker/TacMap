package com.tacmap.drawings

import com.tacmap.localization.LocalizedMessage

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import android.content.Context
import com.tacmap.util.SafeStore
import com.tacmap.util.MissionStorePersistence
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

class DrawingStore private constructor(
    private val file: File,
    private val persistence: MissionStorePersistence,
) {

    constructor(context: Context) : this(
        File(context.filesDir, "drawings.json"),
        MissionStorePersistence.SAFE_STORE,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _document = MutableStateFlow(DrawingDocument())
    val document: StateFlow<DrawingDocument> = _document.asStateFlow()
    /** Last successfully persisted document. UI previews never enter this flow. */
    private val _committedDocument = MutableStateFlow(DrawingDocument())
    val committedDocument: StateFlow<DrawingDocument> = _committedDocument.asStateFlow()
    private val mutationChannel = Channel<ModelMutationEvent>(Channel.UNLIMITED)
    val mutations: Flow<ModelMutationEvent> = mutationChannel.receiveAsFlow()

    /** Non-null when on-disk drawings file was unreadable and got quarantined.
     *  Surfaced by UI so user knows their drawings were preserved (not silently
     *  discarded) rather than thinking blank means "no data". */
    private val _loadError = MutableStateFlow<LocalizedMessage?>(null)
    val loadError: StateFlow<LocalizedMessage?> = _loadError.asStateFlow()

    fun acknowledgeLoadError() { _loadError.value = null }

    private val undoStack = ArrayDeque<DrawingDocument>()
    private val redoStack = ArrayDeque<DrawingDocument>()
    private var previewBase: DrawingDocument? = null
    private var previewObjectId: String? = null

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    init { load() }

    @Synchronized
    fun addFeature(feature: DrawingFeature, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        val before = stableDocument()
        if (before.features.any { it.id == feature.id }) return false
        val candidate = before.copy(features = before.features + feature).withDefaultLayers()
        return commit(before, candidate, setOf(feature.id), origin, recordUndo = true)
    }

    /** One undo snapshot and one atomic persistence write for bounded imports. */
    @Synchronized
    fun addImported(
        layers: List<DrawingLayer>,
        features: List<DrawingFeature>,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        if (layers.isEmpty() && features.isEmpty()) return true
        val before = stableDocument()
        val layerIds = before.layers.asSequence().map { it.id }.toHashSet()
        val featureIds = before.features.asSequence().map { it.id }.toHashSet()
        val newLayers = layers.filter { layerIds.add(it.id) }
        val newFeatures = features.filter { featureIds.add(it.id) }
        if (newLayers.isEmpty() && newFeatures.isEmpty()) return true
        val candidate = before.copy(
            layers = before.layers + newLayers,
            features = before.features + newFeatures,
        ).withDefaultLayers()
        val changed = if (newLayers.isEmpty()) {
            newFeatures.mapTo(HashSet()) { it.id }
        } else {
            candidate.features.mapTo(HashSet()) { it.id }
        }
        return commit(before, candidate, changed, origin, recordUndo = true)
    }

    @Synchronized
    fun updateFeature(feature: DrawingFeature, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        if (previewBase != null && previewObjectId == feature.id) return commitPreview(feature, origin)
        val before = stableDocument()
        if (before.features.none { it.id == feature.id }) return false
        val candidate = before.copy(
            features = before.features.map { if (it.id == feature.id) feature else it },
        ).withDefaultLayers()
        if (candidate == before) return true
        return commit(before, candidate, setOf(feature.id), origin, recordUndo = true)
    }

    @Synchronized
    fun removeFeature(featureId: String, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        val before = stableDocument()
        if (before.features.none { it.id == featureId }) return false
        val candidate = before.copy(
            features = before.features.filterNot { it.id == featureId },
        ).withDefaultLayers()
        return commit(before, candidate, setOf(featureId), origin, recordUndo = true)
    }

    @Synchronized
    fun addLayer(name: String, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        val before = stableDocument()
        val cleanName = name.trim().ifBlank { L10n.text("Layer %1\$s", before.layers.size + 1) }
        val candidate = before.copy(
            layers = before.layers + DrawingLayer(
                name = cleanName,
                color = nextCustomLayerColor(before),
            ),
        ).withDefaultLayers()
        return commit(
            before,
            candidate,
            candidate.features.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = true,
        )
    }

    /**
     * Insert a layer verbatim (preserves supplied id + colour). Used by
     * GeoJSON import so feature.layerId references resolve correctly
     * after round-trip. No-op if layer with same id already exists.
     */
    @Synchronized
    fun addLayerVerbatim(layer: DrawingLayer, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        val before = stableDocument()
        if (before.layers.any { it.id == layer.id }) return true
        val candidate = before.copy(
            layers = before.layers + layer,
        ).withDefaultLayers()
        return commit(
            before,
            candidate,
            candidate.features.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = false,
        )
    }

    @Synchronized
    fun setLayerVisible(
        layerId: String,
        visible: Boolean,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        val before = stableDocument()
        if (before.layers.none { it.id == layerId && it.isVisible != visible }) return true
        val candidate = before.copy(
            layers = before.layers.map {
                if (it.id == layerId) it.copy(isVisible = visible) else it
            },
        ).withDefaultLayers()
        return commit(
            before,
            candidate,
            candidate.features.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = false,
        )
    }

    @Synchronized
    fun renameLayer(
        layerId: String,
        name: String,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        if (layerId in DrawingDocument.DEFAULT_LAYER_IDS) return false
        val before = stableDocument()
        val cleanName = name.trim()
        if (cleanName.isBlank()) return false
        if (before.layers.none { it.id == layerId }) return false
        val candidate = before.copy(
            layers = before.layers.map { if (it.id == layerId) it.copy(name = cleanName) else it },
        ).withDefaultLayers()
        if (candidate == before) return true
        return commit(
            before,
            candidate,
            candidate.features.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = true,
        )
    }

    @Synchronized
    fun recolorLayer(
        layerId: String,
        color: Int,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        if (layerId in DrawingDocument.DEFAULT_LAYER_IDS) return false
        val before = stableDocument()
        if (before.layers.none { it.id == layerId }) return false
        val opaqueColor = color or 0xFF000000.toInt()
        val candidate = before.copy(
            layers = before.layers.map { if (it.id == layerId) it.copy(color = opaqueColor) else it },
        ).withDefaultLayers()
        if (candidate == before) return true
        return commit(
            before,
            candidate,
            candidate.features.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = true,
        )
    }

    /** Rename and recolour a custom layer as one durable candidate mutation. */
    @Synchronized
    fun updateLayer(
        layerId: String,
        name: String,
        color: Int,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        if (layerId in DrawingDocument.DEFAULT_LAYER_IDS) return false
        val before = stableDocument()
        val cleanName = name.trim()
        if (cleanName.isBlank() || before.layers.none { it.id == layerId }) return false
        val opaqueColor = color or 0xFF000000.toInt()
        val candidate = before.copy(
            layers = before.layers.map { layer ->
                if (layer.id == layerId) layer.copy(name = cleanName, color = opaqueColor) else layer
            },
        ).withDefaultLayers()
        if (candidate == before) return true
        return commit(
            before,
            candidate,
            candidate.features.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = true,
        )
    }

    /** Remove a custom layer only after atomically reassigning all drawings to
     * a real fallback in the same persisted document. */
    @Synchronized
    fun deleteLayerReassigningFeatures(
        layerId: String,
        fallbackLayerId: String,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        if (layerId in DrawingDocument.DEFAULT_LAYER_IDS || layerId == fallbackLayerId) return false
        val before = stableDocument()
        if (before.layers.none { it.id == layerId }) return false
        if (before.layers.none { it.id == fallbackLayerId }) return false
        val movedIds = before.features
            .filter { it.layerId == layerId }
            .mapTo(HashSet()) { it.id }
        val candidate = before.copy(
            layers = before.layers.filterNot { it.id == layerId },
            features = before.features.map { feature ->
                if (feature.id in movedIds) feature.copy(layerId = fallbackLayerId) else feature
            },
        ).withDefaultLayers()
        return commit(
            before,
            candidate,
            // Layer metadata is embedded in exported objects, so all drawings
            // are invalidated even when no geometry moved.
            candidate.features.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = true,
        )
    }

    /** Update feature for visual feedback during a gesture (e.g. slider drag)
     *  without pushing to undo stack. Call [updateFeature] at gesture end. */
    @Synchronized
    fun updateFeatureNoUndo(
        feature: DrawingFeature,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        val before = stableDocument()
        if (before.features.none { it.id == feature.id }) return false
        val candidate = before.copy(
            features = before.features.map { if (it.id == feature.id) feature else it },
        ).withDefaultLayers()
        if (candidate == before) return true
        return commit(before, candidate, setOf(feature.id), origin, recordUndo = false)
    }

    /** In-memory-only gesture preview: no disk write, undo entry, or sync event. */
    @Synchronized
    fun previewFeature(feature: DrawingFeature): Boolean {
        val activeBase = previewBase
        if (activeBase == null || previewObjectId != feature.id) {
            revertPreview()
            val before = _document.value
            if (before.features.none { it.id == feature.id }) return false
            previewBase = before
            previewObjectId = feature.id
        }
        val base = checkNotNull(previewBase)
        _document.value = base.copy(
            features = base.features.map { if (it.id == feature.id) feature else it },
        ).withDefaultLayers()
        return true
    }

    @Synchronized
    fun commitPreview(
        feature: DrawingFeature,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        val before = previewBase
        if (before == null || previewObjectId != feature.id) return updateFeature(feature, origin)
        val candidate = before.copy(
            features = before.features.map { if (it.id == feature.id) feature else it },
        ).withDefaultLayers()
        previewBase = null
        previewObjectId = null
        if (candidate == before) {
            _document.value = before
            return true
        }
        if (!persistCandidate(candidate)) {
            _document.value = before
            return false
        }
        pushUndo(before)
        _committedDocument.value = candidate
        _document.value = candidate
        emit(setOf(feature.id), origin)
        return true
    }

    @Synchronized
    fun revertPreview() {
        previewBase?.let { _document.value = it }
        previewBase = null
        previewObjectId = null
    }

    @Synchronized
    fun undo(): Boolean {
        revertPreview()
        val snapshot = undoStack.lastOrNull() ?: return false
        val before = _document.value
        val changed = changedIds(before, snapshot)
        if (!persistCandidate(snapshot)) return false
        undoStack.removeLast()
        redoStack.addLast(before)
        _committedDocument.value = snapshot
        _document.value = snapshot
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        emit(changed, ModelMutationOrigin.LOCAL)
        return true
    }

    @Synchronized
    fun redo(): Boolean {
        revertPreview()
        val snapshot = redoStack.lastOrNull() ?: return false
        val before = _document.value
        val changed = changedIds(before, snapshot)
        if (!persistCandidate(snapshot)) return false
        redoStack.removeLast()
        undoStack.addLast(before)
        _committedDocument.value = snapshot
        _document.value = snapshot
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        emit(changed, ModelMutationOrigin.LOCAL)
        return true
    }

    private fun pushUndo(snapshot: DrawingDocument) {
        if (undoStack.size >= 50) undoStack.removeFirst()
        undoStack.addLast(snapshot)
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
            is SafeStore.LoadResult.Loaded -> {
                val loaded = r.value.withDefaultLayers()
                _committedDocument.value = loaded
                _document.value = loaded
            }
            is SafeStore.LoadResult.Empty -> Unit // fresh install, keep default document
            is SafeStore.LoadResult.Corrupt ->
                // Do NOT overwrite: unreadable file is preserved as
                // drawings.json.corrupt-* and user is told. Otherwise the next
                // edit would silently persist an empty doc over it.
                _loadError.value = Messages.drawingsQuarantinedMessage(r.quarantinedTo?.name ?: Messages.recoveryCopyFallback())
            is SafeStore.LoadResult.Locked -> {
                _locked.value = true
                _loadError.value = Messages.drawingsAreEncryptedAndLockedMessage((r.error.message).toString())
            }
        }
    }

    private fun persistCandidate(candidate: DrawingDocument): Boolean {
        if (_locked.value) {
            _loadError.value = Messages.drawingsAreLockedAndTheChangeWasNotSavedMessage()
            return false
        }
        return runCatching { persistence.write(file, LABEL, json.encodeToString(candidate)) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    _loadError.value = Messages.couldNotSaveDrawingsToDiskTheChangeWasRevertedMessage((it.message).toString())
                    false
                },
            )
    }

    private fun commit(
        before: DrawingDocument,
        candidate: DrawingDocument,
        changed: Set<String>,
        origin: ModelMutationOrigin,
        recordUndo: Boolean,
    ): Boolean {
        if (!persistCandidate(candidate)) return false
        if (recordUndo) pushUndo(before)
        _committedDocument.value = candidate
        _document.value = candidate
        emit(changed, origin)
        return true
    }

    private fun stableDocument(): DrawingDocument {
        val base = previewBase ?: return _document.value
        _document.value = base
        previewBase = null
        previewObjectId = null
        return base
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
        fun forTests(
            filesDir: File,
            persistence: MissionStorePersistence = MissionStorePersistence.SAFE_STORE,
        ) = DrawingStore(File(filesDir, LABEL), persistence)
    }

    private fun DrawingDocument.withDefaultLayers(): DrawingDocument {
        val existingById = layers.associateBy { it.id }
        val defaults = DrawingDocument.defaultLayers().map { defaultLayer ->
            existingById[defaultLayer.id]?.copy(
                color = defaultLayer.color
            ) ?: defaultLayer
        }
        val customLayers = layers.filterNot { it.id in DrawingDocument.DEFAULT_LAYER_IDS }
        return copy(layers = defaults + customLayers)
    }

    private fun nextCustomLayerColor(document: DrawingDocument): Int {
        val customLayerCount = document.layers.count {
            it.id !in DrawingDocument.DEFAULT_LAYER_IDS
        }
        return DrawingDocument.CUSTOM_LAYER_COLORS[
            customLayerCount % DrawingDocument.CUSTOM_LAYER_COLORS.size
        ]
    }
}
