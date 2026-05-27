package com.tacticalmaps.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.waypoints.Waypoint
import kotlin.math.abs

@Composable
fun SearchDialog(
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    onDismiss: () -> Unit,
    onFlyTo: (lat: Double, lng: Double) -> Unit,
    onWaypointSelected: (String?) -> Unit,
    onDrawingSelected: (String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, waypoints, drawings) {
        buildSearchResults(query, waypoints, drawings)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Name, MGRS, or lat/lon") },
                    placeholder = { Text("56HLH 12345 67890") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(results, key = { it.id }) { result ->
                        SearchResultRow(
                            result = result,
                            onClick = {
                                onFlyTo(result.latitude, result.longitude)
                                onWaypointSelected(result.waypointId)
                                onDrawingSelected(result.drawingId)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            if (result.drawingId == null) Icons.Default.LocationOn else Icons.Default.Draw,
            contentDescription = null,
            tint = Color(0xFFFFA000)
        )
        Column(Modifier.weight(1f)) {
            Text(
                result.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                result.subtitle,
                fontSize = 11.sp,
                fontFamily = if (result.isCoordinate) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val waypointId: String? = null,
    val drawingId: String? = null,
    val isCoordinate: Boolean = false
)

private fun buildSearchResults(
    rawQuery: String,
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>
): List<SearchResult> {
    val query = rawQuery.trim()
    val normalizedQuery = query.lowercase()
    val results = mutableListOf<SearchResult>()

    if (query.isNotBlank()) {
        MgrsFormatter.parse(query)?.let { (lat, lng) ->
            results += SearchResult(
                id = "mgrs:$query",
                title = "MGRS",
                subtitle = MgrsFormatter.format(lat, lng),
                latitude = lat,
                longitude = lng,
                isCoordinate = true
            )
        }

        parseLatLng(query)?.let { (lat, lng) ->
            results += SearchResult(
                id = "latlng:$lat,$lng",
                title = "Latitude / Longitude",
                subtitle = "%.5f, %.5f".format(lat, lng),
                latitude = lat,
                longitude = lng,
                isCoordinate = true
            )
        }
    }

    val waypointMatches = if (query.isBlank()) {
        waypoints.takeLast(8).asReversed()
    } else {
        waypoints.filter { waypoint ->
            waypoint.name.contains(normalizedQuery, ignoreCase = true) ||
                waypoint.kind.displayName.contains(normalizedQuery, ignoreCase = true) ||
                waypoint.kind.categoryDisplayName.contains(normalizedQuery, ignoreCase = true)
        }
    }

    waypointMatches.forEach { waypoint ->
        results += SearchResult(
            id = "waypoint:${waypoint.id}",
            title = waypoint.name,
            subtitle = waypoint.kind.displayName,
            latitude = waypoint.latitude,
            longitude = waypoint.longitude,
            waypointId = waypoint.id
        )
    }

    val drawingMatches = if (query.isBlank()) {
        drawings.takeLast(8).asReversed()
    } else {
        drawings.filter { drawing ->
            drawing.name.contains(normalizedQuery, ignoreCase = true) ||
                drawing.geometry.displayName.contains(normalizedQuery, ignoreCase = true)
        }
    }

    drawingMatches.forEach { drawing ->
        drawing.centerCoordinate()?.let { (lat, lng) ->
            results += SearchResult(
                id = "drawing:${drawing.id}",
                title = drawing.name,
                subtitle = "${drawing.geometry.displayName} - ${drawing.points.size} pts",
                latitude = lat,
                longitude = lng,
                drawingId = drawing.id
            )
        }
    }

    return results.distinctBy { it.id }.take(20)
}

private fun DrawingFeature.centerCoordinate(): Pair<Double, Double>? {
    if (points.isEmpty()) return null
    if (geometry == DrawingGeometry.POINT) {
        val point = points.first()
        return point.latitude to point.longitude
    }
    return points.map { it.latitude }.average() to points.map { it.longitude }.average()
}

private fun parseLatLng(query: String): Pair<Double, Double>? {
    val numbers = Regex("""[-+]?\d+(?:\.\d+)?""")
        .findAll(query)
        .mapNotNull { it.value.toDoubleOrNull() }
        .toList()
    if (numbers.size < 2) return null

    val upper = query.uppercase()
    var lat = numbers[0]
    var lng = numbers[1]
    if ('S' in upper) lat = -abs(lat)
    if ('N' in upper) lat = abs(lat)
    if ('W' in upper) lng = -abs(lng)
    if ('E' in upper) lng = abs(lng)

    return if (lat in -90.0..90.0 && lng in -180.0..180.0) {
        lat to lng
    } else {
        null
    }
}
