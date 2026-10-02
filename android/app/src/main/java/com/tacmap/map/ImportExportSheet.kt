package com.tacmap.map

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.tacmap.ui.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.sp
import com.tacmap.export.MissionObjectExport
import com.tacmap.models.TrackRecordingPhase

/// One bottom sheet that gathers every file import/export action, so the main
/// hamburger menu stays short. The launchers/share intents live in MapScreen;
/// each lambda closes the sheet and fires its action.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSheet(
    /** false while a map import runs (D5-09): PDF + Offline Tiles go grey, nothing gets silently cancelled */
    importsEnabled: Boolean = true,
    onImportSymbolPack: () -> Unit,
    onImportPdf: () -> Unit,
    onImportTiles: () -> Unit,
    onImportGeoJson: () -> Unit,
    onImportKml: () -> Unit,
    onExportGeoJson: () -> Unit,
    onExportGpx: () -> Unit,
    onExportAllData: () -> Unit,
    onExportKml: () -> Unit,
    onExportKmz: () -> Unit,
    hasSavedTrack: Boolean,
    isRecordingTrack: Boolean,
    onDiscardTrack: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                L10n.text("Import / Export"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )
            SectionLabel(L10n.text("IMPORT"))
            SheetRow(Icons.Default.PictureAsPdf, L10n.text("PDF Map"), onImportPdf, enabled = importsEnabled)
            SheetRow(Icons.Default.Map, L10n.text("Offline Tiles"), onImportTiles, enabled = importsEnabled)
            SheetRow(Icons.Default.FileDownload, "GeoJSON", onImportGeoJson)
            SheetRow(Icons.Default.FileDownload, "KML / KMZ", onImportKml)
            SheetRow(Icons.Default.FileDownload, Messages.symbolsImportSymbolPack(), onImportSymbolPack)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(L10n.text("EXPORT"))
            SheetRow(Icons.Default.FileUpload, "GeoJSON", onExportGeoJson)
            SheetRow(Icons.Default.Timeline, L10n.text("GPX Track"), onExportGpx)
            SheetRow(Icons.Default.SelectAll, MissionObjectExport.ACTION_TITLE, onExportAllData)
            SheetRow(Icons.Default.Public, "KML", onExportKml)
            SheetRow(Icons.Default.FolderZip, Messages.exportKmzRow(), onExportKmz)
            if (hasSavedTrack) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionLabel(L10n.text("SAVED TRACK"))
                SheetRow(
                    Icons.Default.DeleteForever,
                    if (isRecordingTrack) L10n.text("Stop & Discard Current Track") else L10n.text("Discard Saved Track"),
                    onDiscardTrack,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** What tapping the on-map recording pill does in each state. */
internal enum class RecordingPillAction { STOP, DISMISS, NONE }

/**
 * iOS stops recording from the pill, and cancels or dismisses the
 * awaiting-location and interrupted states. While starting, the foreground
 * service may not have called startForeground yet, and stopping it then
 * crashes the app, so the pill (like the menu item) ignores taps until
 * recording is live.
 */
internal fun recordingPillAction(phase: TrackRecordingPhase): RecordingPillAction = when (phase) {
    TrackRecordingPhase.Recording -> RecordingPillAction.STOP
    TrackRecordingPhase.AwaitingPermission,
    TrackRecordingPhase.Interrupted -> RecordingPillAction.DISMISS
    TrackRecordingPhase.Starting,
    TrackRecordingPhase.Idle -> RecordingPillAction.NONE
}

/// GPX recording pill under the header. Only a live recording shows the red,
/// pulsing "REC" with its point count; awaiting location, starting and
/// interrupted get their own orange label, so the pill never implies a
/// recording that isn't happening. Mirrors iOS RecordingIndicator.
@Composable
fun RecordingIndicator(
    phase: TrackRecordingPhase,
    pointCount: Int,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val recording = phase == TrackRecordingPhase.Recording
    val transition = rememberInfiniteTransition(label = "rec")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot"
    )
    val title = when (phase) {
        TrackRecordingPhase.Recording -> L10n.text("REC")
        TrackRecordingPhase.AwaitingPermission -> Messages.recordingStatusAwaitingLocation()
        TrackRecordingPhase.Starting -> Messages.recordingStatusStarting()
        TrackRecordingPhase.Interrupted -> Messages.recordingStatusInterrupted()
        TrackRecordingPhase.Idle -> Messages.recordingStatusIdle()
    }
    val description = when (phase) {
        TrackRecordingPhase.Recording -> Messages.recordingPillRecordingA11y(Messages.pointCount(pointCount))
        TrackRecordingPhase.AwaitingPermission -> Messages.recordingPillAwaitingA11y()
        TrackRecordingPhase.Starting -> Messages.recordingPillStartingA11y()
        TrackRecordingPhase.Interrupted -> Messages.recordingPillInterruptedA11y()
        TrackRecordingPhase.Idle -> title
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (recording) Color(0xF2D6362F) else Color(0xF2D1731A))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .clearAndSetSemantics {
                contentDescription = description
                if (onTap != null) role = Role.Button
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        when (phase) {
            TrackRecordingPhase.Recording -> Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = dotAlpha))
            )
            TrackRecordingPhase.Interrupted ->
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            else ->
                Icon(Icons.Default.HourglassTop, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Text(
            title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        if (recording) {
            Text(
                "· " + Messages.pointCount(pointCount),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp
            )
        }
    }
}
