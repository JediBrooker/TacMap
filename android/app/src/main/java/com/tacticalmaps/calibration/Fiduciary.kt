package com.tacticalmaps.calibration

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One known PDF-point ↔ MGRS-grid correspondence. Minimum of 3 are required to fit
 * an affine transform; more produce a least-squares fit and an RMS residual.
 */
@Serializable
data class Fiduciary(
    val id: String = UUID.randomUUID().toString(),
    /** PDF user-space point: origin bottom-left, units = points. */
    val pdfX: Double,
    val pdfY: Double,
    val mgrs: String,
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
) {
    val wgs84: Wgs84Coordinate get() = Wgs84Coordinate(latitude, longitude)
}
