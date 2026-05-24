package com.tacticalmaps.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    // Push pending camera moves (centre-on-user, waypoint nav) to the map.
    LaunchedEffect(pendingTarget) {
        pendingTarget?.let {
            cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(it))
            vm.consumePendingCameraTarget()
        }
    }

    // Mirror camera-idle events back to the VM. `isMoving == false && reason == GESTURE`
    // means the user just finished panning — that flips browse mode on.
    CameraIdleReporter(cameraPositionState, vm)

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.SATELLITE,
                isMyLocationEnabled = vm.locationService.hasPermission()
            ),
            uiSettings = MapUiSettings(
                compassEnabled = false,        // we render our own
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false
            )
        ) {
            waypoints.forEach { wp ->
                Marker(
                    state = MarkerState(position = LatLng(wp.latitude, wp.longitude)),
                    title = wp.name,
                    snippet = wp.elevationLabel
                )
            }
        }

        if (isBrowsing) CrosshairOverlay()

        MgrsHeader(
            mgrs = vm.headerMgrs,
            wgs84 = vm.headerWgs84,
            isBrowsing = isBrowsing,
            accuracy = lastLocation?.accuracy?.toDouble()
        )

        MapBottomBar(
            onCentre = vm::centreOnUser,
            speedKmH = lastLocation?.takeIf { it.hasSpeed() }?.speed?.times(3.6),
            elevationM = lastLocation?.takeIf { it.hasAltitude() }?.altitude,
            lastUpdateEpochMs = lastLocation?.time
        )

        MapSideToolbar(
            onImportPdf = { /* TODO: route through MapSource */ },
            onLayers = { /* TODO: layer picker */ },
            onWaypoints = { /* TODO: waypoint list sheet */ },
            onDraw = { /* TODO: draw mode */ }
        )
    }
}

/** Watches the [CameraPositionState] and forwards idle events to the VM. */
@Composable
private fun CameraIdleReporter(state: CameraPositionState, vm: MapViewModel) {
    LaunchedEffect(state.isMoving, state.position) {
        if (!state.isMoving) {
            // Maps Compose exposes `cameraMoveStartedReason` as an enum; GESTURE
            // means the user dragged/pinched (→ enter browse mode), the other
            // values are programmatic camera moves we shouldn't react to.
            val byUser = state.cameraMoveStartedReason ==
                com.google.maps.android.compose.CameraMoveStartedReason.GESTURE
            vm.onCameraIdle(state.position.target, byUser)
        }
    }
}
