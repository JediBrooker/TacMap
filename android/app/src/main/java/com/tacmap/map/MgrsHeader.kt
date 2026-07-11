package com.tacmap.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MGRS readout card. Caller decides where it sits in the layout.
 * Tap = copy MGRS to clipboard. Long-press = drop a waypoint at the
 * displayed coordinate.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MgrsHeader(
    mgrs: String,
    wgs84: String,
    modifier: Modifier = Modifier,
    elevation: Double? = null,
    elevationApprox: Boolean = false,
    utm: String? = null,
    syncConnected: Boolean = false,
    /// Basemap status shown where the old Live Location/Map Centre label was.
    /// "Online basemap" (red) when pulling internet tiles, "Offline basemap"
    /// (green) when an imported pack/PDF is active, null when neither.
    basemapLabel: String? = null,
    basemapColor: Color = Color.Unspecified,
    /// Grid-magnetic angle for compass work, preformatted e.g. "G-M 13.4°E".
    /// Replaces the old accuracy readout in the bottom-right. null hides it.
    gridMagnetic: String? = null,
    onDropPin: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC000000))
            .combinedClickable(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("MGRS", mgrs))
                    Toast.makeText(context, "MGRS copied", Toast.LENGTH_SHORT).show()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onLongClick = onDropPin?.let { drop ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        drop()
                    }
                }
            )
            // Tight padding so it doesn't dominate the map. Matches
            // the iOS card's 5pt vertical / 14pt horizontal.
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // The "MGRS (Map Centre)/(Your Location)" title used to sit here; dropped
        // as redundant (the big readout is obviously the grid ref). Card leads
        // straight into it now.
        Text(
            text = mgrs,
            color = Color(0xFF8CF28C),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 26.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("WGS84", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp,
                 fontWeight = FontWeight.Bold, lineHeight = 12.sp)
            Text(wgs84, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                 fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
            elevation?.let { metres ->
                Text("·", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp,
                     lineHeight = 12.sp)
                Text(
                    (if (elevationApprox) "~" else "") + "%.0f m MSL".format(metres),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 12.sp
                )
            }
        }
        utm?.let { utmText ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("UTM", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp,
                     fontWeight = FontWeight.Bold, lineHeight = 12.sp)
                Text(utmText, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                     fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The old Live Location / Map Centre label lived here, but the card
            // title already says which one, so this slot now carries the basemap
            // status (red online / green offline) instead.
            if (basemapLabel != null) {
                Text(
                    basemapLabel,
                    color = basemapColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 13.sp
                )
            }
            Spacer(Modifier.weight(1f))
            if (syncConnected) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = SyncBlue,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Unit Sync",
                    color = SyncBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 12.sp
                )
                Spacer(Modifier.weight(1f))
            }
            // Grid-magnetic angle (compass correction off the grid) replaces the
            // old accuracy readout here.
            if (gridMagnetic != null) {
                Text(
                    gridMagnetic,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

private val SyncBlue = Color(0xFF4FA8FF)
