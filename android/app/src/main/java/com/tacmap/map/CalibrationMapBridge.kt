package com.tacmap.map

import com.tacmap.calibration.fiducial.CalibrationCameraAnchor
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.calibration.fiducial.CalibrationSheet
import com.tacmap.map.render.MapCamera

/**
 * Main thread only. CustomMapScreen owns the camera, so it hands out a read of
 * the LIVE camera + the georef it's actually drawing. "Add point" / "Set here"
 * capture through this at tap time, never through vm.cameraLat (published a
 * frame later through a LaunchedEffect) - contract s7.4.
 */
internal class CalibrationMapBridge {
    var live: (() -> Pair<MapCamera, CalibrationDisplay?>)? = null

    fun capture(sheet: CalibrationSheet): CalibrationCapture? {
        val (camera, shown) = live?.invoke() ?: return null
        val g = shown?.georef ?: return null
        return CalibrationCameraAnchor.capture(g, sheet, camera)
    }

    fun camera(): MapCamera? = live?.invoke()?.first
}
