package com.example.gochat.core.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gochat.R
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.websocket.GoChatWebSocket
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Foreground service that shares the user's live location for [DURATION_MS], pushing
 * periodic "live_location" events over the websocket to a conversation. Auto-stops
 * after the window; the receiver renders a live banner (ChatRoomViewModel).
 */
@AndroidEntryPoint
class LiveLocationService : Service() {

    @Inject lateinit var webSocket: GoChatWebSocket
    @Inject lateinit var tokenManager: TokenManager

    private var fused: FusedLocationProviderClient? = null
    private var conversationId = ""
    private val stopHandler = Handler(Looper.getMainLooper())

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            sendUpdate(loc.latitude, loc.longitude, "live_location")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        if (conversationId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startUpdates()
        stopHandler.postDelayed({ stopSelf() }, DURATION_MS)
        return START_STICKY
    }

    private fun startUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .build()
        try {
            fused?.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun sendUpdate(lat: Double, lng: Double, type: String) {
        try {
            webSocket.send(buildJsonObject {
                put("type", type)
                put("event_type", type)
                put("conversation_id", conversationId)
                tokenManager.userId?.let { put("sender_id", it) }
                put("lat", lat)
                put("lng", lng)
            })
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Live location", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle("Sharing live location")
            .setContentText("Your location is being shared for 15 minutes")
            .setOngoing(true)
            .addAction(0, "Stop sharing", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopHandler.removeCallbacksAndMessages(null)
        fused?.removeLocationUpdates(callback)
        // Tell the conversation that live sharing ended.
        sendUpdate(0.0, 0.0, "live_location_stop")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_live_conversation_id"
        const val ACTION_STOP = "com.example.gochat.ACTION_STOP_LIVE_LOCATION"
        private const val CHANNEL_ID = "gochat_live_location"
        private const val NOTIF_ID = 90210
        private const val DURATION_MS = 15 * 60 * 1000L
        private const val UPDATE_INTERVAL_MS = 15_000L

        fun start(context: Context, conversationId: String) {
            val intent = Intent(context, LiveLocationService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveLocationService::class.java))
        }
    }
}
