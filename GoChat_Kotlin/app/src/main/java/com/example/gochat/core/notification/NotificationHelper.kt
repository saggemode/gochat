package com.example.gochat.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.gochat.R
import com.example.gochat.ui.chat.ChatRoomActivity

/**
 * Universal notification helper managing high-priority notification channels,
 * rich heads-up alerts, and deep-linking to ChatRoomActivity.
 */
object NotificationHelper {

    const val CHANNEL_MESSAGES = "gochat_channel_messages_v2"
    const val CHANNEL_GROUPS = "gochat_channel_groups_v2"
    const val CHANNEL_CALLS = "gochat_channel_calls_v2"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            // 1. Direct messages channel
            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Direct Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for private 1-on-1 chats"
                enableLights(true)
                lightColor = 0xFF10B981.toInt()
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setSound(soundUri, audioAttributes)
            }

            // 2. Group messages channel
            val groupChannel = NotificationChannel(
                CHANNEL_GROUPS,
                "Group Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for group conversations"
                enableLights(true)
                lightColor = 0xFF10B981.toInt()
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setSound(soundUri, audioAttributes)
            }

            // 3. VoIP Calls channel
            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Voice & Video Calls",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Incoming call ringing alerts"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500, 500)
                val callSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                setSound(callSound, audioAttributes)
            }

            notificationManager.createNotificationChannels(listOf(messageChannel, groupChannel, callsChannel))
        }
    }

    fun showChatNotification(
        context: Context,
        conversationId: String,
        title: String,
        body: String,
        senderAvatar: String = "",
        isGroup: Boolean = false
    ) {
        val channelId = if (isGroup) CHANNEL_GROUPS else CHANNEL_MESSAGES

        // Intent to launch ChatRoomActivity on tap
        val intent = Intent(context, ChatRoomActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, title)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, senderAvatar)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_chat_bubble_rounded)
            .setColor(ContextCompat.getColor(context, R.color.gochat_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("NotificationHelper", "Missing POST_NOTIFICATIONS permission")
                    return
                }
            }
            
            notificationManager.notify(conversationId.hashCode(), notification)
            Log.d("NotificationHelper", "Notification shown for $conversationId: $title - $body")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to show notification", e)
        }
    }
}
