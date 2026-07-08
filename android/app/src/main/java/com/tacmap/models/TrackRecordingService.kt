package com.tacmap.models

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tacmap.app.TacticalApp

/**
 * Foreground service that keeps GPS location delivery alive while a GPX track
 * is recording and the app is backgrounded or the screen is locked.
 *
 * Runs its own [LocationCallback] so fixes keep flowing into the app-scoped
 * [TrackRecorder] even when the Activity (and its ViewModel) is destroyed.
 */
class TrackRecordingService : Service() {

    private var locationCallback: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }

        if (locationCallback == null && hasLocationPermission()) {
            val recorder = (application as TacticalApp).trackRecorder
            val client = LocationServices.getFusedLocationProviderClient(this)
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { recorder.onLocation(it) }
                }
            }
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .build()
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            locationCallback = cb
        }

        return START_STICKY
    }

    override fun onDestroy() {
        locationCallback?.let {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(it)
        }
        locationCallback = null
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Track recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while TacMap is recording a patrol track." }
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording patrol track")
            .setContentText("TacMap is logging your GPS track.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "track_recording"
        private const val NOTIF_ID = 4201

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TrackRecordingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackRecordingService::class.java))
        }
    }
}
