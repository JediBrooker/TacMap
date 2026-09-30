package com.tacmap.map

import com.tacmap.localization.L10n
import com.tacmap.localization.Messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.models.TrackPoint
import com.tacmap.ui.Dialog
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which export is being previewed before sharing. */
internal enum class ExportPreviewKind { GEOJSON, GPX }

/** One summary line of an export: a catalogue plural noun and its count. */
internal data class ExportCount(val noun: String, val count: Int)

/** Lines with more points than this count as free-draws, as on iOS. */
internal const val FREE_DRAW_MIN_POINTS = 21

/** How much of an export file its preview shows, in UTF-8 bytes (iOS: 4000). */
internal const val EXPORT_PREVIEW_MAX_BYTES = 4_000

/**
 * Per-type counts for the GeoJSON export preview, in the order of iOS
 * `ExportSheet`: units, tasks, markers, lines, free-draws, areas and points.
 * Types with nothing to export are left out. Markers cover plain points and
 * airsoft / SAR / POI markers alike, so every exported symbol is counted.
 */
internal fun geoJsonExportCounts(waypoints: List<Waypoint>, drawings: List<DrawingFeature>): List<ExportCount> {
    val lines = drawings.filter { it.geometry == DrawingGeometry.LINE }
    val freeDraws = lines.count { it.points.size >= FREE_DRAW_MIN_POINTS }
    return listOf(
        ExportCount("unit", waypoints.count { it.kind is WaypointKind.Military }),
        ExportCount("task", waypoints.count { it.kind is WaypointKind.ControlMeasure }),
        ExportCount("marker", waypoints.count { it.kind == WaypointKind.Generic || it.kind is WaypointKind.Marker }),
        ExportCount("line", lines.size - freeDraws),
        ExportCount("free-draw", freeDraws),
        ExportCount("area", drawings.count { it.geometry == DrawingGeometry.POLYGON }),
        ExportCount("point", drawings.count { it.geometry == DrawingGeometry.POINT }),
    ).filter { it.count > 0 }
}

/** The start of an export's text for its preview, and whether it was cut short. */
internal data class ExportPreview(val text: String, val truncated: Boolean)

/**
 * The first [maxBytes] bytes of [content] as UTF-8, like the iOS preview, but
 * never splitting a character across the cut.
 */
internal fun exportPreview(content: String, maxBytes: Int = EXPORT_PREVIEW_MAX_BYTES): ExportPreview {
    var bytes = 0
    var index = 0
    while (index < content.length) {
        val codePoint = content.codePointAt(index)
        val width = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + width > maxBytes) return ExportPreview(content.substring(0, index), truncated = true)
        bytes += width
        index += Character.charCount(codePoint)
    }
    return ExportPreview(content, truncated = false)
}

/**
 * Shown before a GeoJSON export is shared, like the iOS export sheet: what the
 * file holds per type, where it opens, the start of its text, and a Share
 * button for exactly that text.
 */
@Composable
internal fun GeoJsonExportPreviewDialog(
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<DrawingLayer>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val counts = remember(waypoints, drawings) { geoJsonExportCounts(waypoints, drawings) }
    ExportPreviewDialog(
        title = L10n.text("Export GeoJSON"),
        summary = if (counts.isEmpty()) {
            listOf(Icons.Default.Inbox to L10n.text("Nothing to export."))
        } else {
            counts.map { exportCountIcon(it.noun) to L10n.quantity(it.noun, it.count) }
        },
        formatNote = Messages.exportGeojsonFormat(),
        shareLabel = Messages.exportShareGeojson(),
        exportLabel = GEOJSON_EXPORT_LABEL,
        generate = { geoJsonExportText(context, waypoints, drawings, layers) },
        share = { content -> shareGeoJson(context, content) },
        onDismiss = onDismiss,
    )
}

/** Shown before the recorded GPX track is shared, like the iOS GPX export sheet. */
@Composable
internal fun GpxExportPreviewDialog(
    points: List<TrackPoint>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hasTrack = points.isNotEmpty()
    ExportPreviewDialog(
        title = L10n.text("Export GPX"),
        summary = listOf(Icons.Default.Timeline to L10n.quantity("track_recorded", points.size)),
        formatNote = Messages.exportGpxFormat(),
        shareLabel = Messages.exportShareGpx(),
        exportLabel = L10n.text("GPX track"),
        generate = if (hasTrack) {
            { com.tacmap.export.GpxExporter.export(points) }
        } else null,
        unavailableMessage = if (hasTrack) null else Messages.exportGpxEmpty(),
        share = { content -> shareGpx(context, content) },
        onDismiss = onDismiss,
    )
}

private fun exportCountIcon(noun: String): ImageVector = when (noun) {
    "unit" -> Icons.Default.Security
    "task" -> Icons.Default.Flag
    "marker" -> Icons.Default.Place
    "free-draw" -> Icons.Default.Gesture
    "area" -> Icons.Default.Pentagon
    "point" -> Icons.Default.RadioButtonChecked
    else -> Icons.Default.Timeline
}

/**
 * Generates the export once, off the main thread, and shares that same text.
 * With no [generate] there is nothing to export: [unavailableMessage] replaces
 * the Share button and the preview.
 */
@Composable
private fun ExportPreviewDialog(
    title: String,
    summary: List<Pair<ImageVector, String>>,
    formatNote: String,
    shareLabel: String,
    exportLabel: String,
    generate: (() -> String)?,
    share: suspend (String) -> Unit,
    onDismiss: () -> Unit,
    unavailableMessage: String? = null,
) {
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val producer = generate ?: return@LaunchedEffect
        try {
            content = withContext(Dispatchers.Default) { producer() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val preview = remember(content) { content?.let { exportPreview(it) } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp),
            color = Color(0xFF16161A),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onDismiss) { Text(L10n.text("Done")) }
                }

                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    summary.forEach { (icon, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(10.dp))
                            Text(text, color = Color.White, fontSize = 15.sp)
                        }
                    }
                    Text(formatNote, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
                }

                if (unavailableMessage != null) {
                    Text(
                        unavailableMessage,
                        color = Color.White.copy(alpha = 0.62f),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    Button(
                        onClick = { content?.let { text -> scope.launch { share(text) } } },
                        enabled = content != null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF), contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(shareLabel, fontWeight = FontWeight.SemiBold)
                    }
                    if (failed) {
                        Text(
                            L10n.text("Could not generate %1\$s. Check the mission data and try again.", exportLabel),
                            color = Color(0xFFFF8A80),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }

                    Text(
                        Messages.exportPreviewHeading(),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        Text(
                            preview?.let { if (it.truncated) it.text + "\n" + Messages.exportPreviewTruncated() else it.text }
                                ?: "—",
                            color = Color.White.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
