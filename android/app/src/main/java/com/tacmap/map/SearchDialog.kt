package com.tacmap.map

import com.tacmap.localization.L10n

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
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.waypoints.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos

@Composable
fun SearchDialog(
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<DrawingLayer>,
    cameraLat: Double,
    cameraLng: Double,
    onlineLookupsEnabled: Boolean,
    onDismiss: () -> Unit,
    onFlyTo: (lat: Double, lng: Double) -> Unit,
    onWaypointSelected: (String?) -> Unit,
    onDrawingSelected: (String?) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var placeResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var placesUnavailable by remember { mutableStateOf(false) }
    // True = unavailable because online lookups are gated off (actionable via
    // settings), vs genuinely offline / no geocoder.
    var placesGated by remember { mutableStateOf(false) }
    val offline = remember(query, waypoints, drawings, layers, cameraLat, cameraLng) {
        searchOffline(
            rawQuery = query,
            waypoints = waypoints,
            drawings = drawings,
            layers = layers,
            cameraLat = cameraLat,
            cameraLng = cameraLng,
        )
    }

    LaunchedEffect(query, cameraLat, cameraLng, onlineLookupsEnabled) {
        val trimmed = query.trim()
        placeResults = emptyList()
        // Place-name search hits the platform Geocoder, which phones the OS map
        // backend (Google) with the query. Gate it behind the online-lookups
        // OPSEC toggle so nothing leaves the device by default. MGRS/grid/lat-lon
        // parsing below is all offline and always available.
        when (onlinePlaceLookupDecision(trimmed, offline, onlineLookupsEnabled)) {
            OnlinePlaceLookupDecision.SKIP_SHORT_OR_BLANK,
            OnlinePlaceLookupDecision.SKIP_COORDINATE -> {
                placesUnavailable = false
                placesGated = false
                isSearching = false
                return@LaunchedEffect
            }
            OnlinePlaceLookupDecision.DISABLED -> {
                placesUnavailable = true
                placesGated = true
                isSearching = false
                return@LaunchedEffect
            }
            OnlinePlaceLookupDecision.REQUEST_PROVIDER -> Unit
        }
        delay(350)
        placesGated = false
        isSearching = true
        val outcome = performOnlinePlaceLookup(trimmed, offline, onlineLookupsEnabled) {
            searchPlaces(context, trimmed, cameraLat, cameraLng)
        }
        placesUnavailable = outcome.providerUnavailable
        placesGated = outcome.disabled
        placeResults = outcome.results
        isSearching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L10n.text("Search")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(L10n.text("Place, MGRS, grid, or lat/lon")) },
                    placeholder = { Text(L10n.text("Holsworthy, 1885, or 56HLH 12345 67890")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (offline.results.isNotEmpty()) {
                        item(key = "offline-heading") {
                            Text(
                                L10n.text("Mission & coordinates"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFBDBDBD),
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        }
                    }
                    items(offline.results, key = { it.id }) { result ->
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
                    offline.status?.let { status ->
                        item(key = "offline-status") {
                            Text(
                                status,
                                modifier = Modifier.padding(vertical = 8.dp),
                                fontSize = 12.sp,
                                color = Color(0xFFEF9A9A),
                            )
                        }
                    }
                    if (placeResults.isNotEmpty()) {
                        item(key = "places-heading") {
                            Text(
                                L10n.text("Online places"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFBDBDBD),
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                    }
                    items(placeResults, key = { it.id }) { result ->
                        SearchResultRow(
                            result = result,
                            onClick = {
                                onFlyTo(result.latitude, result.longitude)
                                onWaypointSelected(null)
                                onDrawingSelected(null)
                                onDismiss()
                            },
                        )
                    }
                    if (isSearching) {
                        item(key = "searching") {
                            Text(
                                L10n.text("Searching places..."),
                                modifier = Modifier.padding(vertical = 8.dp),
                                fontSize = 12.sp,
                                color = Color(0xFFBDBDBD)
                            )
                        }
                    } else if (placesUnavailable && query.trim().length >= 2) {
                        item(key = "places-unavailable") {
                            Text(
                                if (placesGated) PLACES_DISABLED_STATUS else PLACES_OFFLINE_STATUS,
                                modifier = Modifier.padding(vertical = 8.dp),
                                fontSize = 12.sp,
                                color = Color(0xFFEF9A9A)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(L10n.text("Close"))
            }
        }
    )
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
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

internal data class OfflineSearchResponse(
    val results: List<SearchResult>,
    val status: String? = null,
    /** Coordinate-shaped input is fully handled locally, including invalid
     * ranges. The UI uses this to avoid ever sending grid/coordinate text to a
     * place provider even when online lookups are enabled. */
    val recognizedCoordinateInput: Boolean = false,
)

internal enum class OnlinePlaceLookupDecision {
    SKIP_SHORT_OR_BLANK,
    SKIP_COORDINATE,
    DISABLED,
    REQUEST_PROVIDER,
}

internal data class OnlinePlaceLookupOutcome(
    val results: List<SearchResult>,
    val disabled: Boolean = false,
    val providerUnavailable: Boolean = false,
)

/** The single production decision point between the offline engine and the
 * platform Geocoder. Keeping the provider behind an injected lambda makes the
 * no-egress rule directly testable with a spy. */
internal fun onlinePlaceLookupDecision(
    rawQuery: String,
    offline: OfflineSearchResponse,
    onlineLookups: Boolean,
): OnlinePlaceLookupDecision {
    if (rawQuery.trim().length < 2) return OnlinePlaceLookupDecision.SKIP_SHORT_OR_BLANK
    if (offline.recognizedCoordinateInput) return OnlinePlaceLookupDecision.SKIP_COORDINATE
    if (!onlineLookups) return OnlinePlaceLookupDecision.DISABLED
    return OnlinePlaceLookupDecision.REQUEST_PROVIDER
}

internal suspend fun performOnlinePlaceLookup(
    rawQuery: String,
    offline: OfflineSearchResponse,
    onlineLookups: Boolean,
    provider: suspend () -> List<SearchResult>?,
): OnlinePlaceLookupOutcome = when (
    onlinePlaceLookupDecision(rawQuery, offline, onlineLookups)
) {
    OnlinePlaceLookupDecision.SKIP_SHORT_OR_BLANK,
    OnlinePlaceLookupDecision.SKIP_COORDINATE -> OnlinePlaceLookupOutcome(emptyList())
    OnlinePlaceLookupDecision.DISABLED -> OnlinePlaceLookupOutcome(emptyList(), disabled = true)
    OnlinePlaceLookupDecision.REQUEST_PROVIDER -> {
        val results = provider()
        OnlinePlaceLookupOutcome(
            results = results.orEmpty(),
            providerUnavailable = results == null,
        )
    }
}

internal val PLACES_DISABLED_STATUS: String get() =
    L10n.text("Place-name search is off. Enable online lookups in Settings, Privacy & OPSEC. MGRS, grid and lat/lon still work.")
internal val PLACES_OFFLINE_STATUS: String get() =
    L10n.text("Place search unavailable offline — MGRS, grid and lat/lon still work.")

internal fun buildSearchResults(
    rawQuery: String,
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    cameraLat: Double = 0.0,
    cameraLng: Double = 0.0,
    layers: List<DrawingLayer> = emptyList(),
): List<SearchResult> = searchOffline(
    rawQuery = rawQuery,
    waypoints = waypoints,
    drawings = drawings,
    layers = layers,
    cameraLat = cameraLat,
    cameraLng = cameraLng,
).results

/** Pure, deterministic, offline-first search. This function owns no Context,
 * Geocoder, HTTP client, or callback, so every fixture query is structurally
 * incapable of making a network request. */
internal fun searchOffline(
    rawQuery: String,
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<DrawingLayer>,
    cameraLat: Double = 0.0,
    cameraLng: Double = 0.0,
): OfflineSearchResponse {
    val query = rawQuery.trim()
    val normalizedQuery = query.lowercase()
    if (query.isBlank()) {
        val recent = (
            waypoints.map { it.createdAt to it.toSearchResult() } +
                drawings.mapNotNull { drawing ->
                    drawing.toSearchResult()?.let { drawing.createdAt to it }
                }
            ).sortedWith(
                compareByDescending<Pair<Long, SearchResult>> { it.first }
                    .thenBy { it.second.id }
            ).take(8).map { it.second }
        return OfflineSearchResponse(recent)
    }

    // Supported reduced-precision full MGRS references must resolve to the
    // centre of their square, just like shorthand and Move to MGRS. Keep the
    // formatter fallback below for legacy/bare-prefix inputs it accepts.
    resolveMgrsCoordinate(query, referenceLatitude = null, referenceLongitude = null)?.let { resolved ->
        return OfflineSearchResponse(
            results = listOf(SearchResult(
                id = "coordinate:mgrs",
                title = "MGRS",
                subtitle = L10n.text("%1\$s · Centre of %2\$s grid square", resolved.display, resolved.squareSizeLabel),
                latitude = resolved.latitude,
                longitude = resolved.longitude,
                isCoordinate = true,
            )),
            recognizedCoordinateInput = true,
        )
    }

    MgrsFormatter.parse(query)?.let { (lat, lng) ->
        return OfflineSearchResponse(
            results = listOf(SearchResult(
                id = "coordinate:mgrs",
                title = "MGRS",
                subtitle = MgrsFormatter.format(lat, lng),
                latitude = lat,
                longitude = lng,
                isCoordinate = true,
            )),
            recognizedCoordinateInput = true,
        )
    }

    partialGridResult(query, cameraLat, cameraLng)?.let { partial ->
        return OfflineSearchResponse(
            results = listOf(partial.copy(id = "coordinate:partial-mgrs")),
            recognizedCoordinateInput = true,
        )
    }

    when (val decimal = parseDecimalCoordinate(query)) {
        is DecimalCoordinate.Valid -> return OfflineSearchResponse(
            results = listOf(
                SearchResult(
                    id = "coordinate:lat-lon",
                    title = L10n.text("Latitude / Longitude"),
                    subtitle = "%.5f, %.5f".format(decimal.latitude, decimal.longitude),
                    latitude = decimal.latitude,
                    longitude = decimal.longitude,
                    isCoordinate = true,
                )
            ),
            recognizedCoordinateInput = true,
        )
        DecimalCoordinate.OutOfRange -> return OfflineSearchResponse(
            results = emptyList(),
            status = INVALID_COORDINATE_STATUS,
            recognizedCoordinateInput = true,
        )
        DecimalCoordinate.NotCoordinate -> Unit
    }

    val layerNames = layers.associate { it.id to it.displayName }
    data class Ranked(val rank: Int, val createdAt: Long, val result: SearchResult)
    val ranked = buildList {
        waypoints.forEach { waypoint ->
            matchRank(
                query = normalizedQuery,
                name = waypoint.name,
                notes = waypoint.notes,
                kindOrGeometry = listOf(
                    waypoint.kind.displayName,
                    waypoint.kind.categoryDisplayName,
                ),
                layerName = layerNames[waypoint.layerId],
            )?.let { rank -> add(Ranked(rank, waypoint.createdAt, waypoint.toSearchResult())) }
        }
        drawings.forEach { drawing ->
            matchRank(
                query = normalizedQuery,
                name = drawing.name,
                notes = drawing.notes,
                kindOrGeometry = listOf(drawing.geometry.displayName),
                layerName = layerNames[drawing.layerId],
            )?.let { rank ->
                drawing.toSearchResult()?.let { add(Ranked(rank, drawing.createdAt, it)) }
            }
        }
    }

    return OfflineSearchResponse(
        results = ranked.sortedWith(
            compareBy<Ranked> { it.rank }
                .thenBy { it.createdAt }
                .thenBy { it.result.id }
        ).map { it.result }.distinctBy { it.id }.take(20),
        recognizedCoordinateInput = looksCoordinateShaped(query),
    )
}

private fun matchRank(
    query: String,
    name: String,
    notes: String?,
    kindOrGeometry: List<String>,
    layerName: String?,
): Int? {
    val normalizedName = name.trim().lowercase()
    return when {
        normalizedName == query -> 1
        normalizedName.startsWith(query) -> 2
        normalizedName.contains(query) -> 3
        notes?.contains(query, ignoreCase = true) == true -> 4
        kindOrGeometry.any { it.contains(query, ignoreCase = true) } -> 5
        layerName?.contains(query, ignoreCase = true) == true -> 6
        else -> null
    }
}

private fun Waypoint.toSearchResult() = SearchResult(
    id = "waypoint:$id",
    title = name,
    subtitle = kind.displayName,
    latitude = latitude,
    longitude = longitude,
    waypointId = id,
)

private fun DrawingFeature.toSearchResult(): SearchResult? = centerCoordinate()?.let { (lat, lng) ->
    SearchResult(
        id = "drawing:$id",
        title = name,
        subtitle = L10n.text("%1\$s - %2\$s pts", geometry.displayName, points.size),
        latitude = lat,
        longitude = lng,
        drawingId = id,
    )
}

/** null = geocoder unavailable/errored (e.g. offline), empty list = searched
 *  but no matches. Caller uses this to show the right message. */
private suspend fun searchPlaces(
    context: Context,
    query: String,
    cameraLat: Double,
    cameraLng: Double
): List<SearchResult>? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    val geocoder = Geocoder(context)
    val addresses = runCatching {
        geocoder.getFromLocationNameNearCamera(query, cameraLat, cameraLng)
    }.getOrElse { return@withContext null }.orEmpty()

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
) = run {
    // The Gulf of Guinea origin is a legitimate camera location, not an
    // "unavailable" sentinel. SearchDialog always owns a concrete camera, so
    // consistently use it as the local-search anchor, including at 0°, 0°.
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
}

internal fun partialGridResult(raw: String, cameraLat: Double, cameraLng: Double): SearchResult? {
    val components = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (components.size !in 1..2 || components.any { part -> part.any { !it.isDigit() } }) return null
    if (components.size == 2 && components[0].length != components[1].length) return null
    val digits = components.joinToString(separator = "")
    if (digits.length !in setOf(4, 6, 8, 10)) return null
    val resolved = resolveMgrsCoordinate(digits, cameraLat, cameraLng) ?: return null
    return SearchResult(
        id = "partial-mgrs:${resolved.display.replace(" ", "")}:$digits",
        title = resolved.display,
        subtitle = L10n.text("Centre of %1\$s grid square", resolved.squareSizeLabel),
        latitude = resolved.latitude,
        longitude = resolved.longitude,
        isCoordinate = true
    )
}

private fun DrawingFeature.centerCoordinate(): Pair<Double, Double>? {
    if (points.isEmpty()) return null
    if (geometry == DrawingGeometry.POINT) {
        val point = points.first()
        return point.latitude to point.longitude
    }
    return points.map { it.latitude }.average() to points.map { it.longitude }.average()
}

private sealed interface DecimalCoordinate {
    data class Valid(val latitude: Double, val longitude: Double) : DecimalCoordinate
    data object OutOfRange : DecimalCoordinate
    data object NotCoordinate : DecimalCoordinate
}

private fun parseDecimalCoordinate(query: String): DecimalCoordinate {
    val match = Regex(
        """^\s*([-+]?\d+(?:\.\d+)?)\s*(?:,|\s)\s*([-+]?\d+(?:\.\d+)?)\s*$"""
    ).matchEntire(query) ?: return DecimalCoordinate.NotCoordinate
    val lat = match.groupValues[1].toDoubleOrNull() ?: return DecimalCoordinate.NotCoordinate
    val lng = match.groupValues[2].toDoubleOrNull() ?: return DecimalCoordinate.NotCoordinate
    return if (lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) {
        DecimalCoordinate.Valid(lat, lng)
    } else {
        DecimalCoordinate.OutOfRange
    }
}

/** Conservative privacy classifier for malformed coordinate input. Valid
 * coordinates return earlier; this catches grid/numeric-shaped typos without
 * treating prose that merely contains digits (for example "Route 1885") as a
 * coordinate. */
internal fun looksCoordinateShaped(raw: String): Boolean {
    val query = raw.trim().uppercase()
    if (query.isEmpty()) return false

    // Accept a coordinate-shaped MGRS prefix even when the zone/band, square,
    // spacing, or easting/northing lengths are invalid. Those errors belong to
    // the offline parser and must never fall through to Geocoder.
    if (Regex(
            """^(?:\d{1,2}\s*[C-HJ-NP-X]\s*[A-HJ-NP-Z]\s*[A-HJ-NP-Z]|[ABYZ]\s*[A-HJ-NP-Z]\s*[A-HJ-NP-Z])[\sA-Z0-9+\-]*$"""
        )
            .matches(query)) return true

    // Coordinate words and compass suffixes are permitted, but arbitrary prose
    // is not. E also covers scientific notation (e.g. 1e2, 2), which is still
    // coordinate-shaped even though the strict parser rejects it.
    val withoutCoordinateWords = query.replace(
        Regex("""\b(?:NORTH|SOUTH|EAST|WEST|LAT|LATITUDE|LON|LONG|LONGITUDE)\b"""),
        " ",
    )
    val allowed = Regex("""^[0-9NSEW+\-.,;:\s°'\"/]+$""")
    if (allowed.matches(withoutCoordinateWords) && withoutCoordinateWords.any(Char::isDigit)) return true

    // A comma following a numeric first component is coordinate-shaped even if
    // the second component is malformed prose ("91, north" / "12, unknown").
    // Requiring that numeric prefix keeps names such as "Route 1885" eligible.
    val firstCommaComponent = query.substringBefore(',', missingDelimiterValue = "").trim()
    return firstCommaComponent.firstOrNull()?.let { it.isDigit() || it in "+-." } == true
}

internal val INVALID_COORDINATE_STATUS: String get() =
    L10n.text("Latitude must be between -90 and 90, and longitude between -180 and 180.")
