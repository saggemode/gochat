package com.example.gochat.core.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Minimal foreground service of type `mediaProjection`. Android 10+ (and strictly
 * Android 14+) requires an active foreground service of this type to be running before
 * a MediaProjection screen capture may start. The group-call screen-share flow starts
 * this right before publishing the screen track and stops it when sharing ends.
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing your screen")
            .setContentText("GoChat is sharing your screen in a call")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIF_ID = 7712

        fun start(context: Context) {
            val i = Intent(context, ScreenShareService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, ScreenShareService::class.java)) } catch (_: Exception) {}
        }
    }
}
