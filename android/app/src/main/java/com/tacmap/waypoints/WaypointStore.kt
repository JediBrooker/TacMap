package com.tacmap.waypoints

import android.content.Context
import com.tacmap.util.SafeStore
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
class WaypointStore(context: Context) {

    private val file = File(context.filesDir, "waypoints.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

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

    fun add(wp: Waypoint) { pushUndo(); _waypoints.value = _waypoints.value + wp; persist() }
    fun remove(wp: Waypoint) { pushUndo(); _waypoints.value = _waypoints.value.filterNot { it.id == wp.id }; persist() }
    fun update(wp: Waypoint) {
        pushUndo()
        _waypoints.value = _waypoints.value.map { if (it.id == wp.id) wp else it }
        persist()
    }

    /** Update waypoint for visual feedback during a gesture (e.g. slider drag)
     *  without pushing to undo stack. Call [update] when gesture ends. */
    fun updateNoUndo(wp: Waypoint) {
        _waypoints.value = _waypoints.value.map { if (it.id == wp.id) wp else it }
        persist()
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_waypoints.value)
        _waypoints.value = snapshot
        persist()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_waypoints.value)
        _waypoints.value = snapshot
        persist()
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
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

    private companion object { const val LABEL = "waypoints.json" }
}
