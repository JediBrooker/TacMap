package com.tacmap.map

import com.tacmap.localization.L10n

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.export.MissionObjectExport

/// One bottom sheet that gathers every file import/export action, so the main
/// hamburger menu stays short. The launchers/share intents live in MapScreen;
/// each lambda closes the sheet and fires its action.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSheet(
    onImportPdf: () -> Unit,
    onImportTiles: () -> Unit,
    onImportGeoJson: () -> Unit,
    onImportKml: () -> Unit,
    onExportGeoJson: () -> Unit,
    onExportGpx: () -> Unit,
    onExportAllData: () -> Unit,
    hasSavedTrack: Boolean,
    isRecordingTrack: Boolean,
    onDiscardTrack: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                L10n.text("Import / Export"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )
            SectionLabel(L10n.text("IMPORT"))
            SheetRow(Icons.Default.PictureAsPdf, L10n.text("PDF Map"), onImportPdf)
            SheetRow(Icons.Default.Map, L10n.text("Offline Tiles"), onImportTiles)
            SheetRow(Icons.Default.FileDownload, "GeoJSON", onImportGeoJson)
            SheetRow(Icons.Default.FileDownload, "KML / KMZ", onImportKml)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(L10n.text("EXPORT"))
            SheetRow(Icons.Default.FileUpload, "GeoJSON", onExportGeoJson)
            SheetRow(Icons.Default.Timeline, L10n.text("GPX Track"), onExportGpx)
            SheetRow(Icons.Default.SelectAll, MissionObjectExport.ACTION_TITLE, onExportAllData)
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
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/// Red "REC" pill shown while a GPX track is recording, so the live state is
/// obvious without opening the menu. The dot pulses; tapping stops recording
/// (the menu can also start/stop it).
@Composable
fun RecordingIndicator(
    pointCount: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "rec")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xF2D6362F))
            .clickable(onClick = onStop)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = dotAlpha))
        )
        Text(
            L10n.text("REC"),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            "· $pointCount ${if (pointCount == 1) "pt" else "pts"}",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp
        )
    }
}
