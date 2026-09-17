package com.tacmap.map

import com.tacmap.localization.DisplayFormat

import com.tacmap.localization.L10n

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.settings.CoordinateDisplayType
import com.tacmap.util.copySensitivePlainText

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
                onClickLabel = L10n.text("Copy %1\$s coordinate", coordinateType.displayName),
                role = Role.Button,
                onClick = {
                    val copied = copySensitivePlainText(
                        context,
                        L10n.text("%1\$s coordinate", coordinateType.displayName),
                        primaryCoordinate,
                    )
                    Toast.makeText(
                        context,
                        if (copied) {
                            L10n.text("%1\$s copied", coordinateType.displayName)
                        } else {
                            L10n.text("Unable to copy %1\$s", coordinateType.displayName.lowercase())
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    if (copied) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onLongClickLabel = L10n.text("Drop pin at displayed coordinate"),
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
        FittedHudText(
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



            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        L10n.text("%1\$s coordinate %2\$s", coordinateType.displayName, primaryCoordinate)
                }
        )
        // Immediate operational row: range left, elevation right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (distanceFromUserMetres != null) {
                FittedHudText(
                    L10n.text("FROM ME %1\$s", MeasureFormat.distance(distanceFromUserMetres)),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 12.sp,


                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            FittedHudText(
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
                        FittedHudText(
                            basemapLabel,
                            color = basemapColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 13.sp,


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
                            FittedHudText(
                                L10n.text("Unit Sync"),
                                color = SyncBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 12.sp,

                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (gridMagneticDegrees != null) {
                        FittedHudText(
                            formatGridMagnetic(gridMagneticDegrees, gmMils.value),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 12.sp,

                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel = if (gmMils.value) {
                                        L10n.text("Show grid-magnetic angle in degrees")
                                    } else {
                                        L10n.text("Show grid-magnetic angle in mils")
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
    if (elevation == null) return L10n.text("ELEV —")
    val mark = if (approx) "~" else ""
    return L10n.text("ELEV %1\$s", mark + DisplayFormat.number(elevation, 0) + " m")
}

private val SyncBlue = Color(0xFF4FA8FF)

/** Keep complete operational values visible when the system font size grows.
 * Text is measured with the actual font scale, rather than truncating coordinates.
 */
@Composable
internal fun FittedHudText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.copy(
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        textAlign = textAlign,
    )
    BoxWithConstraints(
        modifier = modifier.semantics(mergeDescendants = true) {},
        contentAlignment = when (textAlign) {
            TextAlign.End -> Alignment.CenterEnd
            TextAlign.Center -> Alignment.Center
            else -> Alignment.CenterStart
        },
    ) {
        val availableWidth = constraints.maxWidth
        val fittedSize = remember(text, style, availableWidth, measurer, density) {
            fun fits(size: Float) = measurer.measure(
                text = text,
                style = style.copy(fontSize = size.sp),
                softWrap = false,
                maxLines = 1,
            ).size.width <= availableWidth
            if (fits(fontSize.value)) fontSize else {
                var lower = 1f
                var upper = fontSize.value
                repeat(12) {
                    val candidate = (lower + upper) / 2f
                    if (fits(candidate)) lower = candidate else upper = candidate
                }
                lower.sp
            }
        }
        Text(
            text = text,
            color = color,
            style = style.copy(
                fontSize = fittedSize,
                lineHeight = (lineHeight.value * fittedSize.value / fontSize.value).sp,
            ),
            softWrap = false,
            maxLines = 1,
        )
    }
}
