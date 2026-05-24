package com.tacticalmaps.waypoints

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A user-placed point of interest, stored in WGS84 so it survives swapping basemaps
 * (Google satellite, GeoPDF, calibrated PDF).
 */
@Serializable
data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("elevation_m") val elevationMetres: Double? = null,
    val kind: WaypointKind = WaypointKind.GENERIC,
    @SerialName("created_at_epoch_ms") val createdAt: Long = System.currentTimeMillis()
) {
    val elevationLabel: String? get() = elevationMetres?.let { "%.0f m".format(it) }
}

@Serializable
enum class WaypointKind {
    @SerialName("generic")     GENERIC,
    @SerialName("camp")        CAMP,
    @SerialName("water")       WATER,
    @SerialName("observation") OBSERVATION,
    @SerialName("drop_zone")   DROP_ZONE,
    @SerialName("hazard")      HAZARD
}
