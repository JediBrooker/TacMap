package com.tacmap.calibration.fiducial

import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.map.render.MapCamera
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** what "Add point" / "Set here" sees at the crosshair */
data class CalibrationCapture(
    val page: PagePoint,
    val onSheet: Boolean,
    val screenPointsPerPagePoint: Double?,
    val zoomHint: Boolean,
) {
    /** off sheet beats the zoom hint, contract s7.4 */
    val status: StatusMessage? get() = when {
        !onSheet -> StatusMessage("calibration_off_sheet")
        zoomHint -> StatusMessage("calibration_zoom_hint")
        else -> null
    }
}

/** panel line 1 + the secondary line (contract s10), with offSheet so the buttons agree */
data class CalibrationPanelStatus(
    val primary: StatusMessage,
    val secondary: StatusMessage?,
    val offSheet: Boolean,
) {
    companion object {
        /**
         * off sheet always wins line 1 (OD-F1), straight after a resume too. a camera so far
         * off the page the georef can't even give a page point (12000 km, other side of the
         * planet) is off sheet as well, no capture there. [cameraKnown] false = nothing
         * published yet, don't cry off sheet on the first frame.
         * secondary (B2): the next corner hint whenever there is one, n = 0 too; the zoom hint
         * takes its place only while the crosshair is on the sheet
         */
        fun of(report: FitReport, capture: CalibrationCapture?, cameraKnown: Boolean = capture != null): CalibrationPanelStatus {
            val offSheet = if (capture != null) !capture.onSheet else cameraKnown
            val primary = if (offSheet) StatusMessage("calibration_off_sheet") else report.primaryStatus
            val secondary = when {
                !offSheet && capture?.zoomHint == true -> StatusMessage("calibration_zoom_hint")
                else -> report.nextCorner?.let { StatusMessage(it.messageKey) }
            }
            return CalibrationPanelStatus(primary, secondary, offSheet)
        }
    }
}

/**
 * Contract s7.3: across a refit keep the PAGE point under the crosshair and the
 * page's on-screen scale, heading untouched. Pinned by
 * calibration_fit_report.json cameraAnchor + capture.
 */
object CalibrationCameraAnchor {
    const val MIN_ZOOM = 2.0
    const val MAX_ZOOM = 22.0
    const val JACOBIAN_STEP_PT = 1.0
    const val ZOOM_HINT_SCREEN_PT_PER_PAGE_PT = 1.0

    /** web mercator world coords, zoom 0, 256 units across */
    fun world(lat: Double, lon: Double): Pair<Double, Double> {
        val x = 256.0 * (lon + 180.0) / 360.0
        val y = 256.0 * (1.0 - ln(tan(PI / 4.0 + lat * PI / 180.0 / 2.0)) / PI) / 2.0
        return x to y
    }

    /** sqrt|det J| of page -> zoom 0 world at [p], central differences, h = 1 pt */
    fun scale(g: PdfGeoreference, p: PagePoint, h: Double = JACOBIAN_STEP_PT): Double? {
        fun w(x: Double, y: Double): Pair<Double, Double>? = g.toWGS84(x, y)?.let { world(it.latitude, it.longitude) }
        val a = w(p.x + h, p.y) ?: return null
        val b = w(p.x - h, p.y) ?: return null
        val c = w(p.x, p.y + h) ?: return null
        val d = w(p.x, p.y - h) ?: return null
        val j11 = (a.first - b.first) / (2 * h)
        val j21 = (a.second - b.second) / (2 * h)
        val j12 = (c.first - d.first) / (2 * h)
        val j22 = (c.second - d.second) / (2 * h)
        return sqrt(abs(j11 * j22 - j12 * j21)).takeIf { it.isFinite() && it > 0.0 }
    }

    fun screenPointsPerPagePoint(g: PdfGeoreference, page: PagePoint, zoom: Double): Double? =
        scale(g, page)?.let { 2.0.pow(zoom) * it }

    /** the camera after [from] -> [to], or null (leave the camera alone) */
    fun adjust(
        camera: MapCamera,
        from: PdfGeoreference,
        to: PdfGeoreference,
        minZoom: Double = MIN_ZOOM,
        maxZoom: Double = MAX_ZOOM,
    ): MapCamera? {
        val p = from.toPage(camera.centerLat, camera.centerLon) ?: return null
        val centre = to.toWGS84(p.x, p.y) ?: return null
        val sOld = scale(from, p) ?: return null
        val sNew = scale(to, p) ?: return null
        val zoom = (camera.zoom + log2(sOld / sNew)).takeIf { it.isFinite() } ?: return null
        return camera.copy(
            centerLat = centre.latitude,
            centerLon = centre.longitude,
            zoom = zoom.coerceIn(minZoom, maxZoom),
        )
    }

    /** unclamped zoom, the fixture reports it for info */
    fun unclampedZoom(camera: MapCamera, from: PdfGeoreference, to: PdfGeoreference): Double? {
        val p = from.toPage(camera.centerLat, camera.centerLon) ?: return null
        val sOld = scale(from, p) ?: return null
        val sNew = scale(to, p) ?: return null
        return camera.zoom + log2(sOld / sNew)
    }

    /** contract s7.4: page = shown georef . toPage(live camera centre) */
    fun capture(g: PdfGeoreference, sheet: CalibrationSheet, camera: MapCamera): CalibrationCapture? {
        val page = g.toPage(camera.centerLat, camera.centerLon) ?: return null
        if (!page.isFinite()) return null
        val spp = screenPointsPerPagePoint(g, page, camera.zoom)
        return CalibrationCapture(
            page = page,
            onSheet = sheet.contains(page),
            screenPointsPerPagePoint = spp,
            zoomHint = spp != null && spp < ZOOM_HINT_SCREEN_PT_PER_PAGE_PT,
        )
    }
}
