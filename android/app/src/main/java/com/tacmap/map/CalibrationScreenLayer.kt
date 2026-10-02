package com.tacmap.map

import android.location.Location
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tacmap.calibration.ImportLimits
import com.tacmap.calibration.fiducial.CalibrationCameraAnchor
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.map.render.MapCamera
import kotlin.math.hypot

/**
 * Everything calibration puts over the map (contract s10): the bottom panel, the
 * zoom buttons on the right edge and the entry card docked at the top. Kept out
 * of MapScreen, which is already past what ART will JIT in one method.
 */
@Composable
internal fun BoxScope.CalibrationScreenLayer(
    vm: MapViewModel,
    ui: CalibrationUiState,
    camera: MapCamera?,
    captureNow: () -> CalibrationCapture?,
    lastLocation: Location?,
    hasPreciseLocation: Boolean,
) {
    // status line: off sheet / zoom hint off the published camera, a frame behind at worst
    val liveCapture = camera?.let { CalibrationCameraAnchor.capture(ui.display.georef, ui.state.sheet, it) }
    CalibrationPanel(
        ui = ui,
        capture = liveCapture,
        cameraKnown = camera != null,
        onUndo = { vm.calibration.undo() },
        onDatum = { vm.calibration.openDatumSheet() },
        onGrid = { vm.calibration.toggleGrid() },
        onPoints = { vm.calibration.openPoints() },
        onAdd = { vm.calibration.beginAdd(captureNow()) },
        onFinish = { if (vm.calibration.finishTapped()) vm.finishCalibration() },
        onMove = { vm.calibration.beginMove(it) },
        onEdit = { vm.calibration.beginEdit(it) },
        onDelete = { vm.calibration.delete(it) },
        onDeselect = { vm.calibration.select(null) },
        onCancelMove = { vm.calibration.cancelMove() },
        onSetHere = { vm.calibration.confirmMove(captureNow()) },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth(),
    )
    CalibrationZoomButtons(
        onZoom = vm::requestZoomStep,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 12.dp),
    )
    val entering = ui.phase as? CalibrationPhase.Entering ?: return
    val number = entering.editingId?.let { ui.state.point(it)?.number } ?: ui.state.nextNumber
    // "Move to crosshair" once the crosshair is > 4 dp off the pending marker
    val offsetDp = camera?.let { cam ->
        val w = ui.display.georef.toWGS84(entering.page.x, entering.page.y) ?: return@let null
        val c = cam.screenPoint(cam.centerLat, cam.centerLon)
        val sp = cam.screenPoint(w.latitude, w.longitude)
        hypot(sp.x - c.x, sp.y - c.y)
    }
    val accuracy = lastLocation?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
    CalibrationEntryCard(
        ui = ui,
        pointNumber = number,
        gpsAccuracyM = accuracy,
        gpsAllowed = hasPreciseLocation && accuracy != null && accuracy <= ImportLimits.GPS_MAX_ACCURACY_M,
        moveToCrosshairVisible = offsetDp != null && CalibrationController.moveToCrosshairVisible(offsetDp),
        onText = vm.calibration::updateEntryText,
        onKind = vm.calibration::setEntryKind,
        onLabel = vm.calibration::setEntryLabel,
        onUseGps = {
            val fix = lastLocation
            if (fix != null && accuracy != null) vm.calibration.useGps(fix.latitude, fix.longitude, accuracy)
        },
        onMoveToCrosshair = { vm.calibration.moveEntryToCrosshair(captureNow()) },
        onCancel = { vm.calibration.cancelEntry() },
        onSave = { vm.calibration.commitEntry() },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .heightIn(max = 420.dp),
    )
}

/** the points / datum sheets and the s2 alerts */
@Composable
internal fun CalibrationSheetsAndDialogs(vm: MapViewModel, ui: CalibrationUiState) {
    when (ui.phase) {
        CalibrationPhase.PointsSheet -> CalibrationPointsSheet(
            ui = ui,
            onSelect = { id ->
                vm.calibration.select(id)
                // fly to it at the current zoom + heading
                ui.state.point(id)?.let { p -> ui.display.georef.toWGS84(p.pagePoint.x, p.pagePoint.y) }
                    ?.let { w -> vm.requestCentre(w.latitude, w.longitude) }
            },
            onDismiss = { vm.calibration.closeSheet() },
        )
        CalibrationPhase.DatumSheet -> CalibrationDatumSheet(
            current = ui.state.datumId,
            onChoose = { vm.calibration.chooseDatum(it) },
            onDismiss = { vm.calibration.dismissDatumSheet() },
        )
        else -> CalibrationDialogs(
            ui = ui,
            ageText = { calibrationAgeText(it) },
            onLeaveKeep = {
                vm.calibration.leave(keep = true)
                vm.endCalibrationPreview()
            },
            onLeaveDiscard = {
                vm.calibration.leave(keep = false)
                vm.endCalibrationPreview()
            },
            onContinue = { vm.calibration.continueCalibrating() },
            onFinishAnyway = {
                vm.calibration.cancelFinish()
                vm.finishCalibration()
            },
            onAddMore = { vm.calibration.cancelFinish() },
            onResume = { vm.calibration.resume() },
            onStartOver = { vm.calibration.startOver() },
            onRetrySave = {
                vm.calibration.cancelFinish()
                vm.finishCalibration()
            },
            onNotNow = { vm.calibration.cancelFinish() },
        )
    }
}
