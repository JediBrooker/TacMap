package com.tacmap.models

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latest GPS fix as a StateFlow so Composables can observe it directly.
 *
 * Deliberately the PLATFORM [LocationManager] with GPS_PROVIDER, not Google's
 * FusedLocationProviderClient. Fused is network-assisted: it phones Google
 * servers for Wi-Fi/cell positioning. GPS_PROVIDER is pure on-device satellites,
 * so acquiring a fix leaks nothing. For a tool whose whole point is not phoning
 * home, that trade (raw GPS, no network assist) is the right one.
 *
 * GPS needs ACCESS_FINE_LOCATION. With only coarse granted we don't fall back to
 * NETWORK_PROVIDER, because that's the network-assisted path we're avoiding - a
 * coarse-only install simply gets no fix, and the app already handles that.
 */
class LocationService(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation.asStateFlow()

    private var isRunning = false

    // API 26 still needs the full interface (the extra methods only got defaults
    // in API 30), so this can't be a lambda.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { _lastLocation.value = location }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in API 29, still required on API 26-28")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(): Boolean =
        hasFineLocationPermission() || hasCoarseLocationPermission()

    private fun hasFineLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        // GPS_PROVIDER requires fine location. Coarse-only can't do dark GPS.
        if (!hasFineLocationPermission()) return
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return

        // Seed with the last known GPS fix so we're not blank while acquiring.
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.let { _lastLocation.value = it }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1_000L, // ~1s, matches the old fused cadence
            0f,
            listener,
            Looper.getMainLooper()
        )
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        locationManager.removeUpdates(listener)
        isRunning = false
    }
}
