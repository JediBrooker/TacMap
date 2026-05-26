package com.tacticalmaps.waypoints

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * In-memory waypoint store with disk persistence to filesDir/waypoints.json.
 * Fresh installs start empty — no demo seed (matches iOS).
 */
class WaypointStore(context: Context) {

    private val file = File(context.filesDir, "waypoints.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    init { load() }

    fun add(wp: Waypoint) { _waypoints.value = _waypoints.value + wp; persist() }
    fun remove(wp: Waypoint) { _waypoints.value = _waypoints.value.filterNot { it.id == wp.id }; persist() }
    fun update(wp: Waypoint) {
        _waypoints.value = _waypoints.value.map { if (it.id == wp.id) wp else it }
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching { json.decodeFromString<List<Waypoint>>(file.readText()) }
            .onSuccess { _waypoints.value = it }
    }

    private fun persist() {
        runCatching { file.writeText(json.encodeToString(_waypoints.value)) }
    }
}
