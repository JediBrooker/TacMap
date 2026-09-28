package com.tacmap.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WbTwilight
import com.tacmap.ui.AlertDialog
import com.tacmap.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.settings.CoordinateDisplayType
import kotlin.math.roundToInt

/** A long-pressed map point and its coordinate in the user's readout format. */
internal data class MapPressPoint(
    val latitude: Double,
    val longitude: Double,
    /** Where the finger was, in map pixels, so the menu opens there. */
    val screen: Offset,
    val coordinate: PrimaryCoordinateDisplay,
) {
    companion object {
        fun at(latitude: Double, longitude: Double, screen: Offset, preference: CoordinateDisplayType) =
            MapPressPoint(
                latitude = latitude,
                longitude = longitude,
                screen = screen,
                coordinate = resolvePrimaryCoordinateDisplay(
                    preference = preference,
                    mgrs = MgrsFormatter.format(latitude, longitude),
                    wgs84 = wgs84Text(latitude, longitude),
                    utm = MgrsFormatter.formatUtm(latitude, longitude),
                ),
            )
    }
}

/**
 * The long-press point menu: place a symbol, measure, add range rings, show
 * sun and moon times, or copy the coordinate. Actions that create mission
 * objects are hidden when [canEdit] is false (graphics locked).
 */
@Composable
internal fun MapPointMenu(
    point: MapPressPoint,
    canEdit: Boolean,
    onPlaceSymbol: (SymbolEditorMode) -> Unit,
    onMeasure: () -> Unit,
    onRangeRings: () -> Unit,
    onSunMoon: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .offset { IntOffset(point.screen.x.roundToInt(), point.screen.y.roundToInt()) }
            .size(1.dp)
    ) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            Text(
                point.coordinate.text,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            if (canEdit) {
                Text(
                    Messages.mapPointPlaceSymbol(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                Item(L10n.text("Military Unit"), Icons.Default.Security) { onPlaceSymbol(SymbolEditorMode.MILITARY) }
                Item(L10n.text("Tactical Task"), Icons.Default.Flag) { onPlaceSymbol(SymbolEditorMode.TASK) }
                Item(L10n.text("Marker"), Icons.Default.Place) { onPlaceSymbol(SymbolEditorMode.MARKER) }
                HorizontalDivider()
            }
            Item(Messages.mapPointMeasure(), Icons.Default.Straighten, onMeasure)
            if (canEdit) Item(Messages.mapPointRangeRings(), Icons.Default.TrackChanges, onRangeRings)
            Item(Messages.mapPointSunMoon(), Icons.Default.WbTwilight, onSunMoon)
            Item(Messages.mapPointCopy(), Icons.Default.ContentCopy, onCopy)
        }
    }
}

@Composable
private fun Item(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Offline sun and moon times for a long-pressed point. */
@Composable
internal fun PointSunMoonDialog(point: MapPressPoint, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(L10n.text("Done")) } },
        title = { Text(Messages.sunMoonTitle()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        point.coordinate.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                SunMoonSection(point.latitude, point.longitude)
            }
        },
    )
}
