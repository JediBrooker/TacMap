package com.tacticalmaps.map

import android.content.Context
import android.location.Geocoder
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.waypoints.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos

@Composable
fun SearchDialog(
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    cameraLat: Double,
    cameraLng: Double,
    onDismiss: () -> Unit,
    onFlyTo: (lat: Double, lng: Double) -> Unit,
    onWaypointSelected: (String?) -> Unit,
    onDrawingSelected: (String?) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var placeResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val localResults = remember(query, waypoints, drawings, cameraLat, cameraLng) {
        buildSearchResults(query, waypoints, drawings, cameraLat, cameraLng)
    }
    val results = remember(localResults, placeResults) {
        (localResults + placeResults).distinctBy { it.id }.take(20)
    }

    LaunchedEffect(query, cameraLat, cameraLng) {
        val trimmed = query.trim()
        placeResults = emptyList()
        if (trimmed.length < 2) {
            isSearching = false
            return@LaunchedEffect
        }
        delay(350)
        isSearching = true
        placeResults = searchPlaces(context, trimmed, cameraLat, cameraLng)
        isSearching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Place, MGRS, grid, or lat/lon") },
                    placeholder = { Text("Holsworthy, 1885, or 56HLH 12345 67890") },
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
                    if (isSearching) {
                        item(key = "searching") {
                            Text(
                                "Searching places...",
                                modifier = Modifier.padding(vertical = 8.dp),
                                fontSize = 12.sp,
                                color = Color(0xFFBDBDBD)
                            )
                        }
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

internal data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val waypointId: String? = null,
    val drawingId: String? = null,
    val isCoordinate: Boolean = false
)

internal fun buildSearchResults(
    rawQuery: String,
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    cameraLat: Double = 0.0,
    cameraLng: Double = 0.0
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

        partialGridResult(query, cameraLat, cameraLng)?.let { results += it }

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

private suspend fun searchPlaces(
    context: Context,
    query: String,
    cameraLat: Double,
    cameraLng: Double
): List<SearchResult> = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext emptyList()
    val geocoder = Geocoder(context)
    val addresses = runCatching {
        geocoder.getFromLocationNameNearCamera(query, cameraLat, cameraLng)
    }.getOrNull().orEmpty()

    addresses
        .filter { it.hasLatitude() && it.hasLongitude() }
        .take(20)
        .mapIndexed { index, address ->
            val title = address.featureName
                ?: address.thoroughfare
                ?: address.locality
                ?: query
            SearchResult(
                id = "place:$index:${address.latitude},${address.longitude}",
                title = title,
                subtitle = address.getAddressLine(0).orEmpty(),
                latitude = address.latitude,
                longitude = address.longitude
            )
        }
}

@Suppress("DEPRECATION")
private fun Geocoder.getFromLocationNameNearCamera(
    query: String,
    cameraLat: Double,
    cameraLng: Double
) = if (cameraLat != 0.0 || cameraLng != 0.0) {
    val latDelta = 200_000.0 / 111_320.0
    val lngDelta = 200_000.0 / (111_320.0 * cos(Math.toRadians(cameraLat)).coerceAtLeast(0.01))
    getFromLocationName(
        query,
        20,
        (cameraLat - latDelta).coerceIn(-90.0, 90.0),
        (cameraLng - lngDelta).coerceIn(-180.0, 180.0),
        (cameraLat + latDelta).coerceIn(-90.0, 90.0),
        (cameraLng + lngDelta).coerceIn(-180.0, 180.0)
    )
} else {
    getFromLocationName(query, 20)
}

internal fun partialGridResult(raw: String, cameraLat: Double, cameraLng: Double): SearchResult? {
    val digits = raw.filter { it.isDigit() }
    if (digits.length !in setOf(4, 6, 8, 10)) return null
    if (cameraLat == 0.0 && cameraLng == 0.0) return null

    val prefix = extractGzdPrefix(MgrsFormatter.format(cameraLat, cameraLng, spaced = false)) ?: return null
    val half = digits.length / 2
    val easting = digits.take(half)
    val northing = digits.takeLast(half)
    val (southwestLat, southwestLng) = MgrsFormatter.parse("$prefix$easting$northing") ?: return null
    val (lat, lng) = centreOfMgrsSquare(southwestLat, southwestLng, half)
    val squareSize = when (half) {
        2 -> "1 km"
        3 -> "100 m"
        4 -> "10 m"
        5 -> "1 m"
        else -> ""
    }
    return SearchResult(
        id = "partial-mgrs:$prefix:$digits",
        title = "$prefix $easting $northing",
        subtitle = "Centre of $squareSize grid square",
        latitude = lat,
        longitude = lng,
        isCoordinate = true
    )
}

private fun extractGzdPrefix(mgrs: String): String? {
    val compact = mgrs.uppercase().filterNot { it.isWhitespace() }
    return Regex("""^(\d{1,2}[A-Z][A-Z]{2})""").find(compact)?.groupValues?.getOrNull(1)
        ?: Regex("""^([ABYZ][A-Z]{2})""").find(compact)?.groupValues?.getOrNull(1)
}

private fun centreOfMgrsSquare(southwestLat: Double, southwestLng: Double, eastNorthDigits: Int): Pair<Double, Double> {
    val halfMetres = when (eastNorthDigits) {
        2 -> 500.0
        3 -> 50.0
        4 -> 5.0
        5 -> 0.5
        else -> 0.0
    }
    val lat = southwestLat + halfMetres / 111_320.0
    val lng = southwestLng + halfMetres / (111_320.0 * cos(Math.toRadians(southwestLat)).coerceAtLeast(0.01))
    return lat to lng
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
