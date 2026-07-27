package com.tacmap.map

import com.tacmap.settings.CoordinateDisplayType

/** The resolved header coordinate. [type] may differ from the preference when
 *  the requested grid format is unavailable at the current latitude. */
internal data class PrimaryCoordinateDisplay(
    val type: CoordinateDisplayType,
    val text: String,
)

internal fun resolvePrimaryCoordinateDisplay(
    preference: CoordinateDisplayType,
    mgrs: String,
    wgs84: String,
    utm: String?,
): PrimaryCoordinateDisplay = when (preference) {
    CoordinateDisplayType.MGRS ->
        PrimaryCoordinateDisplay(CoordinateDisplayType.MGRS, mgrs)
    CoordinateDisplayType.WGS84 ->
        PrimaryCoordinateDisplay(CoordinateDisplayType.WGS84, wgs84)
    CoordinateDisplayType.UTM -> {
        val availableUtm = utm?.takeIf {
            it.isNotBlank() && !it.trimStart().startsWith("N/A", ignoreCase = true)
        }
        if (availableUtm != null) {
            PrimaryCoordinateDisplay(CoordinateDisplayType.UTM, availableUtm)
        } else {
            PrimaryCoordinateDisplay(CoordinateDisplayType.WGS84, wgs84)
        }
    }
}
