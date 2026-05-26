package com.tacticalmaps.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.tacticalmaps.waypoints.WaypointStore

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val context = LocalContext.current

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }

    val isBrowsing by vm.isBrowsing.collectAsState()
    val pendingTarget by vm.pendingCameraTarget.collectAsState()
    val waypointStore = remember { WaypointStore(context) }
    val waypoints by waypointStore.waypoints.collectAsState()
    val lastLocation by vm.locationService.lastLocation.collectAsState()
    val selectedWaypointId by vm.selectedWaypointId.collectAsState()

    // Sheet / menu state.
    var showWaypointSheet by remember { mutableStateOf(false) }
    var hamburgerOpen by remember { mutableStateOf(false) }

    // Location permission.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.locationService.start()
        }
    }
    LaunchedEffect(Unit) {
        if (vm.locationService.hasPermission()) {
            vm.locationService.start()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // Programmatic camera moves (centre-on-user, fly-to-waypoint).
    LaunchedEffect(pendingTarget) {
        pendingTarget?.let {
            cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(it))
            vm.consumePendingCameraTarget()
        }
    }

    CameraIdleReporter(cameraPositionState, vm)

    // Selected waypoint (recomputed on every render so it stays in sync
    // with the store).
    val selected = waypoints.firstOrNull { it.id == selectedWaypointId }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.SATELLITE,
                isMyLocationEnabled = vm.locationService.hasPermission()
            ),
            uiSettings = MapUiSettings(
                compassEnabled = false,
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false
            ),
            onMapClick = {
                // Tap on the map (anywhere not a marker) dismisses the
                // selection card — matches the iOS tap-to-dismiss flow.
                if (selectedWaypointId != null) vm.selectWaypoint(null)
            }
        ) {
            waypoints.forEach { wp ->
                Marker(
                    state = MarkerState(position = LatLng(wp.latitude, wp.longitude)),
                    title = wp.name,
                    snippet = wp.elevationLabel,
                    onClick = {
                        vm.selectWaypoint(wp.id)
                        true  // suppress default info-window so our card is the only UI
                    }
                )
            }
        }

        // Crosshair: always on (iOS shows it whenever not drawing —
        // we have no drawing mode in Phase 1 so it's always on).
        CrosshairOverlay()

        MgrsHeader(
            mgrs = vm.headerMgrs,
            wgs84 = vm.headerWgs84,
            isBrowsing = isBrowsing,
            accuracy = lastLocation?.accuracy?.toDouble()
        )

        // Hamburger (left) + Compass (right), below the header.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 130.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                CircleHudButton(Icons.Default.Menu) { hamburgerOpen = true }
                DropdownMenu(
                    expanded = hamburgerOpen,
                    onDismissRequest = { hamburgerOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Symbology") },
                        onClick = {
                            hamburgerOpen = false
                            showWaypointSheet = true
                        }
                    )
                    // Phase 2+ entries (Search, Drawings, Layers, Import PDF,
                    // Export, About) come later.
                }
            }
            CompassChip()
        }

        // Bottom area: centre-on-location pill OR floating controls card
        // (mutually exclusive, matches iOS).
        if (selected != null) {
            SymbolControlsCard(
                waypoint = selected,
                crosshairTargetLat = cameraPositionState.position.target.latitude,
                crosshairTargetLng = cameraPositionState.position.target.longitude,
                store = waypointStore,
                onDismiss = { vm.selectWaypoint(null) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .fillMaxWidth()
            )
        } else {
            CentrePill(
                onClick = { vm.centreOnUser() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }

    // Waypoint list sheet.
    if (showWaypointSheet) {
        WaypointListSheet(
            waypoints = waypoints,
            crosshairLat = cameraPositionState.position.target.latitude,
            crosshairLng = cameraPositionState.position.target.longitude,
            store = waypointStore,
            onDismiss = { showWaypointSheet = false },
            onFlyTo = { ll ->
                vm.flyTo(ll)
                showWaypointSheet = false
            }
        )
    }
}

@Composable
private fun CircleHudButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null, tint = Color.White,
                 modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CompassChip() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CentrePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC000000)),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color.White,
             modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text("Centre on My Location", color = Color.White,
             fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CameraIdleReporter(state: CameraPositionState, vm: MapViewModel) {
    LaunchedEffect(state.isMoving, state.position) {
        if (!state.isMoving) {
            val byUser = state.cameraMoveStartedReason ==
                com.google.maps.android.compose.CameraMoveStartedReason.GESTURE
            vm.onCameraIdle(state.position.target, byUser)
        }
    }
}
