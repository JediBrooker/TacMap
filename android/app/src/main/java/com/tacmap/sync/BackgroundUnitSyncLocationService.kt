package com.tacmap.sync

import com.tacmap.localization.LocalizedMessage

import com.tacmap.localization.Messages

import android.content.res.Configuration
import com.tacmap.localization.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

import com.tacmap.localization.L10n

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tacmap.app.MainActivity
import com.tacmap.app.TacticalApp
import com.tacmap.settings.BackgroundUnitSyncInterval

/**
 * Location-only foreground service for opted-in screen-off Unit Sync.
 *
 * The service receives no room code, keys, callsign, or socket. Those remain in
 * the app-scoped runtime, and Android never restarts this service after process
 * death. It supplies only fresh GPS callbacks to the restricted v3 session.
 */
class BackgroundUnitSyncLocationService : Service(), UnitSyncRuntime.ServiceController {
    private var activeGeneration: Long? = null
    private var locationListener: LocationListener? = null
    private var selectedInterval: BackgroundUnitSyncInterval? = null

    private val runtime: UnitSyncRuntime
        get() = (application as TacticalApp).unitSyncRuntime

    private val languageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        languageScope.launch {
            AppLanguage.selections.drop(1).collect { refreshNotificationLanguage() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshNotificationLanguage()
    }

    /** Update the existing notification only; never start a session or touch GPS. */
    private fun refreshNotificationLanguage() {
        val generation = activeGeneration ?: return
        if (!runtime.isServiceSessionAuthorized(generation)) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(generation)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            activeGeneration?.let(runtime::userStoppedBackgroundSharing)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val generation = intent?.getLongExtra(EXTRA_GENERATION, INVALID_GENERATION)
            ?: INVALID_GENERATION
        if (generation == INVALID_GENERATION) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val interval = runtime.attachService(generation, this)
        if (interval == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (activeGeneration != null && activeGeneration != generation) removeLocationListener()
        activeGeneration = generation

        try {
            val notification = buildNotification(generation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            if (!hasPreciseLocation()) {
                fail(
                    generation,
                    Messages.syncPreciseLocationIsUnavailableBackgroundUnitSyncLocationStoppedMessage(),
                )
                return START_NOT_STICKY
            }
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                fail(generation, Messages.syncGpsIsTurnedOffBackgroundUnitSyncLocationStoppedMessage())
                return START_NOT_STICKY
            }
            registerLocationListener(interval)
        } catch (failure: RuntimeException) {
            fail(
                generation,
                Messages.backgroundSyncLocationStoppedMessage(failure.message ?: failure.javaClass.simpleName),
            )
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun updateInterval(interval: BackgroundUnitSyncInterval) {
        if (activeGeneration == null || selectedInterval == interval) return
        if (!hasPreciseLocation()) {
            activeGeneration?.let { generation ->
                fail(generation, Messages.syncPreciseLocationIsUnavailableBackgroundUnitSyncLocationStoppedMessage())
            }
            return
        }
        registerLocationListener(interval)
    }

    override fun onDestroy() {
        languageScope.cancel()
        removeLocationListener()
        val generation = activeGeneration
        activeGeneration = null
        selectedInterval = null
        if (generation != null) runtime.detachService(generation, this)
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationListener(interval: BackgroundUnitSyncInterval) {
        removeLocationListener()
        val generation = activeGeneration ?: return
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!hasPreciseLocation()) {
                    fail(
                        generation,
                        Messages.syncPreciseLocationIsUnavailableBackgroundUnitSyncLocationStoppedMessage(),
                    )
                    return
                }
                runtime.onServiceLocation(generation, location)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) {
                    fail(generation, Messages.syncGpsWasTurnedOffBackgroundUnitSyncLocationStoppedMessage())
                }
            }

            @Deprecated("Deprecated in API 29, still required on API 26-28")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            interval.minutes.toLong() * 60_000L,
            0f,
            listener,
            Looper.getMainLooper(),
        )
        locationListener = listener
        selectedInterval = interval
    }

    private fun removeLocationListener() {
        locationListener?.let { listener ->
            runCatching {
                (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                    .removeUpdates(listener)
            }
        }
        locationListener = null
    }

    private fun fail(generation: Long, message: LocalizedMessage) {
        runtime.onServiceUnavailable(generation, message)
        stopSelf()
    }

    private fun hasPreciseLocation(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(generation: Long): Notification {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                L10n.text("Background Unit Sync"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = L10n.text("Shown while TacMap can share location with Unit Sync when the screen is off.")
            }
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopSharing = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, BackgroundUnitSyncLocationService::class.java).apply {
                action = ACTION_STOP_SHARING
                putExtra(EXTRA_GENERATION, generation)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(L10n.text("Background Unit Sync is on"))
            .setContentText(L10n.text("TacMap can update your encrypted unit location with the screen off."))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openApp)
            .addAction(0, L10n.text("Stop sharing"), stopSharing)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "background_unit_sync"
        private const val NOTIFICATION_ID = 4202
        private const val STOP_REQUEST_CODE = 4202
        private const val EXTRA_GENERATION = "com.tacmap.sync.extra.GENERATION"
        private const val ACTION_STOP_SHARING = "com.tacmap.sync.action.STOP_BACKGROUND_SHARING"
        private const val INVALID_GENERATION = -1L

        fun start(context: Context, generation: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BackgroundUnitSyncLocationService::class.java)
                    .putExtra(EXTRA_GENERATION, generation),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundUnitSyncLocationService::class.java))
        }
    }
}
