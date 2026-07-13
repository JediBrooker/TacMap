package com.tacmap.waypoints

import android.content.Context
import com.tacmap.util.SafeStore
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
class WaypointStore private constructor(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "waypoints.json"))

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()
    private val mutationChannel = Channel<ModelMutationEvent>(Channel.UNLIMITED)
    val mutations: Flow<ModelMutationEvent> = mutationChannel.receiveAsFlow()

    /** Non-null when on-disk waypoints file was unreadable and got quarantined,
     *  so UI can tell user their waypoints were preserved instead of just
     *  silently emptied. */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    fun acknowledgeLoadError() { _loadError.value = null }

    private val undoStack = ArrayDeque<List<Waypoint>>()
    private val redoStack = ArrayDeque<List<Waypoint>>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    init { load() }

    fun add(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        pushUndo(); _waypoints.value = _waypoints.value + wp; persist(); emit(setOf(wp.id), origin)
    }
    fun addAll(waypoints: List<Waypoint>, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (waypoints.isEmpty()) return
        pushUndo()
        _waypoints.value = _waypoints.value + waypoints
        persist()
        emit(waypoints.mapTo(HashSet()) { it.id }, origin)
    }
    fun remove(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_waypoints.value.none { it.id == wp.id }) return
        pushUndo(); _waypoints.value = _waypoints.value.filterNot { it.id == wp.id }; persist(); emit(setOf(wp.id), origin)
    }
    fun update(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_waypoints.value.none { it.id == wp.id } || _waypoints.value.first { it.id == wp.id } == wp) return
        pushUndo()
        _waypoints.value = _waypoints.value.map { if (it.id == wp.id) wp else it }
        persist()
        emit(setOf(wp.id), origin)
    }

    /** Update waypoint for visual feedback during a gesture (e.g. slider drag)
     *  without pushing to undo stack. Call [update] when gesture ends. */
    fun updateNoUndo(wp: Waypoint, origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL) {
        if (_waypoints.value.none { it.id == wp.id } || _waypoints.value.first { it.id == wp.id } == wp) return
        _waypoints.value = _waypoints.value.map { if (it.id == wp.id) wp else it }
        persist()
        emit(setOf(wp.id), origin)
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        val changed = changedIds(_waypoints.value, snapshot)
        redoStack.addLast(_waypoints.value)
        _waypoints.value = snapshot
        persist()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        emit(changed, ModelMutationOrigin.LOCAL)
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        val changed = changedIds(_waypoints.value, snapshot)
        undoStack.addLast(_waypoints.value)
        _waypoints.value = snapshot
        persist()
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        emit(changed, ModelMutationOrigin.LOCAL)
    }

    private fun pushUndo() {
        if (undoStack.size >= 50) undoStack.removeFirst()
        undoStack.addLast(_waypoints.value)
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
            is SafeStore.LoadResult.Loaded -> _waypoints.value = r.value
            is SafeStore.LoadResult.Empty -> Unit
            is SafeStore.LoadResult.Corrupt ->
                _loadError.value = "Saved waypoints could not be read and were set aside " +
                    "(${r.quarantinedTo?.name ?: "recovery copy"}). Starting with no waypoints."
            is SafeStore.LoadResult.Locked -> {
                _locked.value = true
                _loadError.value = "Waypoints are encrypted and locked. ${r.error.message}"
            }
        }
    }

    private fun persist() {
        // Refuse to write while locked. The file on disk is fine, we just
        // can't read it yet, and an empty list must never land on top of it.
        if (_locked.value) return
        runCatching { SafeStore.writeAtomically(file, LABEL, json.encodeToString(_waypoints.value)) }
            .onFailure { _loadError.value = "Could not save waypoints to disk: ${it.message}" }
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
        fun forTests(filesDir: File) = WaypointStore(File(filesDir, LABEL))
    }
}
