package com.tacmap.waypoints

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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * In-memory waypoint store with disk persistence to filesDir/waypoints.json.
 * Fresh installs start empty, no demo seed (matches iOS).
 */
class WaypointStore private constructor(
    private val file: File,
    private val persistence: MissionStorePersistence,
) {

    constructor(context: Context) : this(
        File(context.filesDir, "waypoints.json"),
        MissionStorePersistence.SAFE_STORE,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()
    /** Last successfully persisted state. UI previews never enter this flow. */
    private val _committedWaypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val committedWaypoints: StateFlow<List<Waypoint>> = _committedWaypoints.asStateFlow()
    private val mutationChannel = Channel<ModelMutationEvent>(Channel.UNLIMITED)
    val mutations: Flow<ModelMutationEvent> = mutationChannel.receiveAsFlow()

    /** Non-null when on-disk waypoints file was unreadable and got quarantined,
     *  so UI can tell user their waypoints were preserved instead of just
     *  silently emptied. */
    private val _loadError = MutableStateFlow<LocalizedMessage?>(null)
    val loadError: StateFlow<LocalizedMessage?> = _loadError.asStateFlow()

    fun acknowledgeLoadError() { _loadError.value = null }

    private val undoStack = ArrayDeque<List<Waypoint>>()
    private val redoStack = ArrayDeque<List<Waypoint>>()
    private var previewBase: List<Waypoint>? = null
    private var previewObjectId: String? = null

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    init { load() }

    @Synchronized
    fun add(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        val before = stableState()
        if (before.any { it.id == wp.id }) return false
        return commit(before, before + wp, setOf(wp.id), origin, recordUndo = true)
    }

    @Synchronized
    fun addAll(waypoints: List<Waypoint>, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        if (waypoints.isEmpty()) return true
        val before = stableState()
        val occupied = before.mapTo(HashSet()) { it.id }
        val additions = waypoints.filter { occupied.add(it.id) }
        if (additions.isEmpty()) return true
        return commit(
            before,
            before + additions,
            additions.mapTo(HashSet()) { it.id },
            origin,
            recordUndo = true,
        )
    }

    @Synchronized
    fun remove(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        val before = stableState()
        if (before.none { it.id == wp.id }) return false
        return commit(before, before.filterNot { it.id == wp.id }, setOf(wp.id), origin, recordUndo = true)
    }

    @Synchronized
    fun update(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        if (previewBase != null && previewObjectId == wp.id) return commitPreview(wp, origin)
        val before = stableState()
        if (before.none { it.id == wp.id }) return false
        val candidate = before.map { if (it.id == wp.id) wp else it }
        if (candidate == before) return true
        return commit(before, candidate, setOf(wp.id), origin, recordUndo = true)
    }

    /** Reassign every waypoint before a layer is removed. One persisted
     * snapshot prevents a partially moved set and keeps undo/sync bounded. */
    @Synchronized
    fun reassignLayer(
        fromLayerId: String,
        toLayerId: String,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        require(fromLayerId != toLayerId) { "Fallback layer must differ from deleted layer" }
        val before = stableState()
        val changed = before.filter { it.layerId == fromLayerId }.mapTo(HashSet()) { it.id }
        if (changed.isEmpty()) return true
        val candidate = before.map { waypoint ->
            if (waypoint.id in changed) waypoint.copy(layerId = toLayerId) else waypoint
        }
        return commit(before, candidate, changed, origin, recordUndo = true)
    }

    /** Update waypoint for visual feedback during a gesture (e.g. slider drag)
     *  without pushing to undo stack. Call [update] when gesture ends. */
    @Synchronized
    fun updateNoUndo(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL): Boolean {
        val before = stableState()
        if (before.none { it.id == wp.id }) return false
        val candidate = before.map { if (it.id == wp.id) wp else it }
        if (candidate == before) return true
        return commit(before, candidate, setOf(wp.id), origin, recordUndo = false)
    }

    /** In-memory-only gesture preview: no disk write, undo entry, or sync event. */
    @Synchronized
    fun previewUpdate(wp: Waypoint): Boolean {
        val activeBase = previewBase
        if (activeBase == null || previewObjectId != wp.id) {
            revertPreview()
            val before = _waypoints.value
            if (before.none { it.id == wp.id }) return false
            previewBase = before
            previewObjectId = wp.id
        }
        val base = checkNotNull(previewBase)
        _waypoints.value = base.map { if (it.id == wp.id) wp else it }
        return true
    }

    @Synchronized
    fun commitPreview(
        wp: Waypoint,
        origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
    ): Boolean {
        val before = previewBase
        if (before == null || previewObjectId != wp.id) return update(wp, origin)
        val candidate = before.map { if (it.id == wp.id) wp else it }
        previewBase = null
        previewObjectId = null
        if (candidate == before) {
            _waypoints.value = before
            return true
        }
        if (!persistCandidate(candidate)) {
            _waypoints.value = before
            return false
        }
        pushUndo(before)
        _committedWaypoints.value = candidate
        _waypoints.value = candidate
        emit(setOf(wp.id), origin)
        return true
    }

    @Synchronized
    fun revertPreview() {
        previewBase?.let { _waypoints.value = it }
        previewBase = null
        previewObjectId = null
    }

    @Synchronized
    fun undo(): Boolean {
        revertPreview()
        val snapshot = undoStack.lastOrNull() ?: return false
        val before = _waypoints.value
        val changed = changedIds(before, snapshot)
        if (!persistCandidate(snapshot)) return false
        undoStack.removeLast()
        redoStack.addLast(before)
        _committedWaypoints.value = snapshot
        _waypoints.value = snapshot
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        emit(changed, ModelMutationOrigin.LOCAL)
        return true
    }

    @Synchronized
    fun redo(): Boolean {
        revertPreview()
        val snapshot = redoStack.lastOrNull() ?: return false
        val before = _waypoints.value
        val changed = changedIds(before, snapshot)
        if (!persistCandidate(snapshot)) return false
        redoStack.removeLast()
        undoStack.addLast(before)
        _committedWaypoints.value = snapshot
        _waypoints.value = snapshot
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        emit(changed, ModelMutationOrigin.LOCAL)
        return true
    }

    private fun pushUndo(snapshot: List<Waypoint>) {
        if (undoStack.size >= 50) undoStack.removeFirst()
        undoStack.addLast(snapshot)
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    /** True when the store couldn't be opened b/c the at-rest key is locked.
     *  Nothing may be persisted while this holds or we'd write an empty list
     *  over data we simply couldn't read. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private fun load() {
        when (val r = SafeStore.readOrQuarantine(file, LABEL) { json.decodeFromString<List<Waypoint>>(it) }) {
            is SafeStore.LoadResult.Loaded -> {
                _committedWaypoints.value = r.value
                _waypoints.value = r.value
            }
            is SafeStore.LoadResult.Empty -> Unit
            is SafeStore.LoadResult.Corrupt ->
                _loadError.value = Messages.waypointsQuarantinedMessage(r.quarantinedTo?.name ?: Messages.recoveryCopyFallback())
            is SafeStore.LoadResult.Locked -> {
                _locked.value = true
                _loadError.value = Messages.waypointsAreEncryptedAndLockedMessage((r.error.message).toString())
            }
        }
    }

    private fun persistCandidate(candidate: List<Waypoint>): Boolean {
        // Refuse to write while locked. The file on disk is fine, we just
        // can't read it yet, and an empty list must never land on top of it.
        if (_locked.value) {
            _loadError.value = Messages.waypointsAreLockedAndTheChangeWasNotSavedMessage()
            return false
        }
        return runCatching { persistence.write(file, LABEL, json.encodeToString(candidate)) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    _loadError.value = Messages.couldNotSaveWaypointsToDiskTheChangeWasRevertedMessage((it.message).toString())
                    false
                },
            )
    }

    private fun commit(
        before: List<Waypoint>,
        candidate: List<Waypoint>,
        changed: Set<String>,
        origin: ModelMutationOrigin,
        recordUndo: Boolean,
    ): Boolean {
        if (!persistCandidate(candidate)) return false
        if (recordUndo) pushUndo(before)
        _committedWaypoints.value = candidate
        _waypoints.value = candidate
        emit(changed, origin)
        return true
    }

    private fun stableState(): List<Waypoint> {
        val base = previewBase ?: return _waypoints.value
        _waypoints.value = base
        previewBase = null
        previewObjectId = null
        return base
    }

    private fun emit(ids: Set<String>, origin: ModelMutationOrigin) {
        if (ids.isNotEmpty()) check(mutationChannel.trySend(ModelMutationEvent(ids, origin)).isSuccess)
    }

    private fun changedIds(before: List<Waypoint>, after: List<Waypoint>): Set<String> {
        val a = before.associateBy { it.id }; val b = after.associateBy { it.id }
        return (a.keys + b.keys).filterTo(HashSet()) { a[it] != b[it] }
    }

    internal companion object {
        private const val LABEL = "waypoints.json"
        fun forTests(
            filesDir: File,
            persistence: MissionStorePersistence = MissionStorePersistence.SAFE_STORE,
        ) = WaypointStore(File(filesDir, LABEL), persistence)
    }
}
