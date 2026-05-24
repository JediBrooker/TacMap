package com.tacticalmaps.mgrs

import com.google.android.gms.maps.model.LatLng
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import mil.nga.grid.features.Point

/**
 * Wrapper around NGA `mgrs` library. All overlays in TacticalMaps store WGS84;
 * MGRS strings are presentation only.
 */
object MgrsFormatter {

    /** 1-metre precision (10-digit) MGRS readout. */
    val defaultPrecision: GridType = GridType.METER

    fun format(latLng: LatLng, precision: GridType = defaultPrecision, spaced: Boolean = true): String {
        val mgrs = MGRS.from(Point.point(latLng.longitude, latLng.latitude))
        val raw = mgrs.coordinate(precision)
        return if (spaced) raw else raw.replace(" ", "")
    }

    /** Decode a string like `56H LH 12345 67890` to a LatLng. Returns null on parse failure. */
    fun parse(s: String): LatLng? = try {
        val m = MGRS.parse(s)
        val p = m.toPoint()
        LatLng(p.latitude, p.longitude)
    } catch (_: Throwable) {
        null
    }
}
