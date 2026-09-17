package com.tacmap.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Decides when foreground Unit Sync needs to ask GPS for a fresh fix.
 *
 * A continuously registered provider can be throttled while the device is
 * stationary. Presence frames must never make an old coordinate look current,
 * so the client asks [LocationManager.GPS_PROVIDER] for another real fix before
 * the peer's foreground retention window can expire.
 */
internal class ForegroundPresenceLiveness {
    private var lastRequestElapsedRealtimeNanos: Long? = null

    fun shouldRequestFreshFix(
        lastSuccessfulFixElapsedRealtimeNanos: Long?,
        nowElapsedRealtimeNanos: Long,
        force: Boolean = false,
    ): Boolean {
        val lastRequest = lastRequestElapsedRealtimeNanos
        if (lastRequest != null) {
            if (nowElapsedRealtimeNanos < lastRequest) return false
            if (nowElapsedRealtimeNanos - lastRequest < secondsToNanos(MIN_REQUEST_INTERVAL_SECONDS)) {
                return false
            }
        }
        if (force) return true
        val lastFix = lastSuccessfulFixElapsedRealtimeNanos ?: return true
        if (nowElapsedRealtimeNanos < lastFix) return false
        return nowElapsedRealtimeNanos - lastFix >= secondsToNanos(REQUEST_AFTER_SECONDS)
    }

    fun recordRequest(nowElapsedRealtimeNanos: Long) {
        lastRequestElapsedRealtimeNanos = nowElapsedRealtimeNanos
    }

    fun reset() {
        lastRequestElapsedRealtimeNanos = null
    }

    companion object {
        /** Leaves 25 seconds for a new fix before the 45-second peer expiry. */
        const val REQUEST_AFTER_SECONDS = 20L
        const val MIN_REQUEST_INTERVAL_SECONDS = 5L
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val MAX_REQUESTED_FIX_AGE_SECONDS =
            UnitSyncPresenceCadence.MAX_AUTHENTICATED_SESSION_SEED_AGE_SECONDS

        fun isGenuinelyFreshRequestedFix(
            baselineFixElapsedRealtimeNanos: Long?,
            candidateFixElapsedRealtimeNanos: Long,
            nowElapsedRealtimeNanos: Long,
        ): Boolean {
            if (baselineFixElapsedRealtimeNanos != null &&
                candidateFixElapsedRealtimeNanos <= baselineFixElapsedRealtimeNanos
            ) return false
            return isGenuinelyRecentFix(
                candidateFixElapsedRealtimeNanos,
                nowElapsedRealtimeNanos,
            )
        }

        fun isGenuinelyRecentFix(
            candidateFixElapsedRealtimeNanos: Long,
            nowElapsedRealtimeNanos: Long,
        ): Boolean {
            if (candidateFixElapsedRealtimeNanos > nowElapsedRealtimeNanos) return false
            return nowElapsedRealtimeNanos - candidateFixElapsedRealtimeNanos <=
                secondsToNanos(MAX_REQUESTED_FIX_AGE_SECONDS)
        }

        private fun secondsToNanos(seconds: Long): Long = seconds * 1_000_000_000L
    }
}

/** One in-flight, GPS-only foreground fix request. */
internal class ForegroundGpsFixRequester(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeListener: LocationListener? = null
    private var activeTimeout: Runnable? = null
    private var activeCompletion: ((Location?) -> Unit)? = null

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    fun request(completion: (Location?) -> Unit): Boolean {
        if (activeListener != null) return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (!runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
        ) return false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(this, location)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in API 29, still required on API 26-28")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val timeout = Runnable { finish(listener, null) }
        activeListener = listener
        activeTimeout = timeout
        activeCompletion = completion

        return runCatching {
            mainHandler.postDelayed(timeout, ForegroundPresenceLiveness.REQUEST_TIMEOUT_MS)
            locationManager.requestSingleUpdate(
                LocationManager.GPS_PROVIDER,
                listener,
                Looper.getMainLooper(),
            )
            true
        }.getOrElse {
            clear(listener)
            false
        }
    }

    @Synchronized
    fun cancel() {
        val listener = activeListener ?: return
        clear(listener)
    }

    private fun finish(listener: LocationListener, location: Location?) {
        val completion = synchronized(this) {
            if (activeListener !== listener) return
            val callback = activeCompletion
            clear(listener)
            callback
        }
        completion?.invoke(location)
    }

    @SuppressLint("MissingPermission")
    private fun clear(listener: LocationListener) {
        if (activeListener !== listener) return
        activeTimeout?.let(mainHandler::removeCallbacks)
        runCatching { locationManager.removeUpdates(listener) }
        activeListener = null
        activeTimeout = null
        activeCompletion = null
    }
}

internal fun isCurrentPendingV3Handshake(
    expectedConnectionGeneration: Long,
    activeConnectionGeneration: Long,
    expectedSocketIsCurrent: Boolean,
    protocolVersion: Int,
    isHandshakePending: Boolean,
): Boolean = expectedSocketIsCurrent &&
    expectedConnectionGeneration == activeConnectionGeneration &&
    protocolVersion == 3 &&
    isHandshakePending
