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
 * Seeds demo waypoints on first run so the prototype looks like the mockup.
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
        if (!file.exists()) { seedDemo(); return }
        runCatching { json.decodeFromString<List<Waypoint>>(file.readText()) }
            .onSuccess { _waypoints.value = it }
            .onFailure { seedDemo() }
    }

    private fun persist() {
        runCatching { file.writeText(json.encodeToString(_waypoints.value)) }
    }

    private fun seedDemo() {
        _waypoints.value = listOf(
            Waypoint(name = "Camp Alpha",        latitude = 37.7820, longitude = -122.4310, elevationMetres = 2345.0, kind = WaypointKind.CAMP),
            Waypoint(name = "Water Source",      latitude = 37.7750, longitude = -122.4250, elevationMetres = 1856.0, kind = WaypointKind.WATER),
            Waypoint(name = "Observation Point", latitude = 37.7790, longitude = -122.4080, elevationMetres = 2120.0, kind = WaypointKind.OBSERVATION),
            Waypoint(name = "Drop Zone",         latitude = 37.7730, longitude = -122.4140, elevationMetres = 1620.0, kind = WaypointKind.DROP_ZONE)
        )
        persist()
    }
}
