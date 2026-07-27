package com.tacmap.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ClipDescription
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.settings.CoordinateDisplayType

/**
 * Primary coordinate readout card. Caller decides where it sits in the layout.
 * Tap copies the displayed coordinate. Long-press drops a waypoint there.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MgrsHeader(
    primaryCoordinate: String,
    coordinateType: CoordinateDisplayType,
    modifier: Modifier = Modifier,
    elevation: Double? = null,
    elevationApprox: Boolean = false,
    syncConnected: Boolean = false,
    /// Basemap status shown where the old Live Location/Map Centre label was.
    /// "Online basemap" (red) when pulling internet tiles, "Offline basemap"
    /// (green) when an imported pack/PDF is active, null when neither.
    basemapLabel: String? = null,
    basemapColor: Color = Color.Unspecified,
    /// Grid-magnetic angle for compass work, raw degrees (+E / -W). Shown
    /// bottom-right in mils by default; tap it to flip to degrees. null hides it.
    gridMagneticDegrees: Double? = null,
    /// Straight-line distance from the latest user fix to the crosshair.
    distanceFromUserMetres: Double? = null,
    onDropPin: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val gmMils = rememberPersistedBoolean("gridMagneticMils", true)
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC000000))
            .combinedClickable(
                onClickLabel = "Copy ${coordinateType.displayName} coordinate",
                role = Role.Button,
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText(
                        "${coordinateType.displayName} coordinate",
                        primaryCoordinate
                    ).apply {
                        description.extras = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    cm?.setPrimaryClip(clip)
                    Handler(Looper.getMainLooper()).postDelayed({
                        val current = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (current == primaryCoordinate) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
                            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }, 60_000L)
                    Toast.makeText(
                        context,
                        "${coordinateType.displayName} copied",
                        Toast.LENGTH_SHORT
                    ).show()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onLongClickLabel = "Drop pin at displayed coordinate",
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
        // The old source title was redundant; the selected primary coordinate
        // now leads the card directly.
        Text(
            text = primaryCoordinate,
            color = Color(0xFF8CF28C),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = when (coordinateType) {
                CoordinateDisplayType.MGRS -> 24.sp
                CoordinateDisplayType.WGS84 -> 16.sp
                CoordinateDisplayType.UTM -> 18.sp
            },
            lineHeight = 26.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "${coordinateType.displayName} coordinate $primaryCoordinate"
                }
        )
        // Immediate operational row: range left, elevation right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (distanceFromUserMetres != null) {
                Text(
                    "FROM ME ${MeasureFormat.distance(distanceFromUserMetres)}",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                elevationText(elevation, elevationApprox),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp
            )
        }
        if (basemapLabel != null || syncConnected || gridMagneticDegrees != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Equal-width slots keep Unit Sync geometrically centred in the
                // card regardless of the status text on either side.
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (basemapLabel != null) {
                        Text(
                            basemapLabel,
                            color = basemapColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (syncConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                lineHeight = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (gridMagneticDegrees != null) {
                        Text(
                            formatGridMagnetic(gridMagneticDegrees, gmMils.value),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel = if (gmMils.value) {
                                        "Show grid-magnetic angle in degrees"
                                    } else {
                                        "Show grid-magnetic angle in mils"
                                    }
                                ) {
                                    gmMils.value = !gmMils.value
                                }
                        )
                    }
                }
            }
        }
    }
}

/// iOS-matching elevation readout: "ELEV 0 m", "ELEV ~1025 m" (~ = approximate
/// / offline cache), or "ELEV —" when there's no reading.
private fun elevationText(elevation: Double?, approx: Boolean): String {
    if (elevation == null) return "ELEV —"
    val mark = if (approx) "~" else ""
    return "ELEV %s%.0f m".format(mark, elevation)
}

private val SyncBlue = Color(0xFF4FA8FF)
