package com.tacmap.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process alive and location delivery running
 * while a GPX track is recording and the app is backgrounded or the screen is
 * locked. It does NOT request locations itself — [LocationService] keeps
 * streaming fixes into [TrackRecorder] (which persists each one) for as long as
 * this service holds the app in the foreground, so a patrol track no longer
 * gaps or vanishes when the phone goes in a pocket.
 *
 * NOTE: this needs on-device verification (foreground-service behaviour can't be
 * exercised by the JVM unit tests). Failure to start the service degrades
 * gracefully — foreground recording + the on-disk log still capture points.
 */
class TrackRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

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
