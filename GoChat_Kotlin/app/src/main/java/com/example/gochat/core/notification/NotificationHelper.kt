package com.example.gochat.core.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.ui.calls.CallActivity
import com.example.gochat.ui.chat.ChatRoomActivity
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Universal notification helper managing high-priority notification channels,
 * rich heads-up alerts with direct reply, mark as read, circular avatars, and deep-linking.
 */
object NotificationHelper {

    const val CHANNEL_MESSAGES = "gochat_channel_messages_v2"
    const val CHANNEL_GROUPS = "gochat_channel_groups_v2"
    const val CHANNEL_CALLS = "gochat_channel_calls_v2"

    const val KEY_TEXT_REPLY = "key_text_reply"
    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    const val EXTRA_CONVERSATION_TITLE = "extra_conversation_title"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    const val ACTION_DIRECT_REPLY = "com.example.gochat.ACTION_DIRECT_REPLY"
    const val ACTION_MARK_AS_READ = "com.example.gochat.ACTION_MARK_AS_READ"
    const val ACTION_DISMISS_CALL = "com.example.gochat.ACTION_DISMISS_CALL"

    fun getNotificationIdForConversation(conversationId: String): Int {
        return if (conversationId.isNotBlank()) (conversationId.hashCode() and 0x7FFFFFFF) else 1001
    }

    fun dismissNotification(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val notifId = getNotificationIdForConversation(conversationId)
            notificationManager.cancel(notifId)
            Log.d("NotificationHelper", "Dismissed notification for conversation $conversationId (id=$notifId)")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to dismiss notification for $conversationId", e)
        }
    }

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
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
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
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
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
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(listOf(messageChannel, groupChannel, callsChannel))
        }
    }

    suspend fun loadCircularBitmap(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            withTimeoutOrNull(2000L) {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .transformations(CircleCropTransformation())
                    .build()
                val result = context.imageLoader.execute(request)
                (result.drawable as? BitmapDrawable)?.bitmap
            }
        } catch (e: Exception) {
            Log.w("NotificationHelper", "Could not load circular avatar: ${e.message}")
            null
        }
    }

    suspend fun showChatNotificationAsync(
        context: Context,
        conversationId: String,
        title: String,
        body: String,
        senderAvatar: String = "",
        isGroup: Boolean = false,
        isMuted: Boolean = false
    ) {
        val bitmap = if (senderAvatar.isNotBlank()) loadCircularBitmap(context, senderAvatar) else null
        showChatNotification(context, conversationId, title, body, senderAvatar, isGroup, isMuted, bitmap)
    }

    fun showChatNotification(
        context: Context,
        conversationId: String,
        title: String,
        body: String,
        senderAvatar: String = "",
        isGroup: Boolean = false,
        isMuted: Boolean = false,
        avatarBitmap: Bitmap? = null
    ) {
        createNotificationChannels(context)

        val channelId = if (isGroup) CHANNEL_GROUPS else CHANNEL_MESSAGES
        val notificationId = getNotificationIdForConversation(conversationId)
        val displayTitle = if (title.isBlank() || title.equals("User", ignoreCase = true) || title.equals("Chat", ignoreCase = true)) {
            "GoChat Contact"
        } else {
            title
        }
        val displayBody = body.ifBlank { "New message" }

        // 1. PendingIntent for notification tap with parent back stack to MainActivity
        val tapIntent = Intent(context, ChatRoomActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, displayTitle)
            putExtra(ChatRoomActivity.EXTRA_CONVERSATION_AVATAR, senderAvatar)
        }

        val tapPendingIntent = try {
            TaskStackBuilder.create(context).run {
                addNextIntentWithParentStack(tapIntent)
                getPendingIntent(
                    notificationId,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        } catch (e: Exception) {
            Log.w("NotificationHelper", "TaskStackBuilder failed, using direct PendingIntent", e)
            PendingIntent.getActivity(
                context,
                notificationId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // 2. Direct Reply action with RemoteInput
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DIRECT_REPLY
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_CONVERSATION_TITLE, displayTitle)
        }

        // RemoteInput requires FLAG_MUTABLE on API 31+
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        // 3. Mark as Read action
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_AS_READ
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadAction = NotificationCompat.Action.Builder(
            0,
            "Mark as Read",
            markReadPendingIntent
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        // 4. Build Person & MessagingStyle
        val userPerson = Person.Builder().setName("Me").build()
        val senderPersonBuilder = Person.Builder().setName(displayTitle)
        if (avatarBitmap != null) {
            senderPersonBuilder.setIcon(IconCompat.createWithBitmap(avatarBitmap))
        }
        val senderPerson = senderPersonBuilder.build()

        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(if (isGroup) displayTitle else null)
            .setGroupConversation(isGroup)
            .addMessage(displayBody, System.currentTimeMillis(), senderPerson)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_chat_bubble_rounded)
            .setColor(ContextCompat.getColor(context, R.color.gochat_accent))
            .setContentTitle(displayTitle)
            .setContentText(displayBody)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(replyAction)
            .addAction(markReadAction)

        if (avatarBitmap != null) {
            builder.setLargeIcon(avatarBitmap)
        }

        if (isMuted) {
            builder.setSilent(true)
            builder.setDefaults(0)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (!notificationManager.areNotificationsEnabled()) {
                Log.w("NotificationHelper", "Notifications are completely disabled for this app by user in OS settings!")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("NotificationHelper", "Missing POST_NOTIFICATIONS permission")
                    return
                }
            }
            notificationManager.notify(notificationId, builder.build())
            Log.d("NotificationHelper", "Notification shown for $conversationId: $displayTitle (id=$notificationId)")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to show notification", e)
        }
    }

    /**
     * Updates an active notification with the user's inline direct reply.
     * Appending the reply to MessagingStyle stops the system RemoteInput progress spinner
     * and displays immediate confirmation to the user.
     */
    fun updateNotificationWithReply(
        context: Context,
        conversationId: String,
        notificationId: Int,
        replyText: String,
        senderTitle: String = "GoChat Contact"
    ) {
        if (conversationId.isBlank()) return
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val channelId = CHANNEL_MESSAGES

            val userPerson = Person.Builder().setName("You").build()
            val senderPerson = Person.Builder().setName(senderTitle).build()

            val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
                .setConversationTitle(null)
                .setGroupConversation(false)
                .addMessage(replyText, System.currentTimeMillis(), userPerson)

            val tapIntent = Intent(context, ChatRoomActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ChatRoomActivity.EXTRA_CONVERSATION_ID, conversationId)
                putExtra(ChatRoomActivity.EXTRA_CONVERSATION_TITLE, senderTitle)
            }
            val tapPendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_chat_bubble_rounded)
                .setColor(ContextCompat.getColor(context, R.color.gochat_accent))
                .setContentTitle(senderTitle)
                .setContentText("You: $replyText")
                .setStyle(messagingStyle)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(tapPendingIntent)
                .setTimeoutAfter(3000L) // Auto dismiss after 3 seconds once replied

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }
            notificationManager.notify(notificationId, builder.build())
            Log.d("NotificationHelper", "Updated notification with reply for conv=$conversationId (id=$notificationId)")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to update notification with reply", e)
        }
    }

    suspend fun showCallNotificationAsync(
        context: Context,
        callId: String,
        callerId: String,
        callerName: String,
        callType: String = "voice",
        callerAvatar: String = ""
    ) {
        val bitmap = if (callerAvatar.isNotBlank()) loadCircularBitmap(context, callerAvatar) else null
        showCallNotification(context, callId, callerId, callerName, callType, callerAvatar, bitmap)
    }

    fun showCallNotification(
        context: Context,
        callId: String,
        callerId: String,
        callerName: String,
        callType: String = "voice",
        callerAvatar: String = "",
        avatarBitmap: Bitmap? = null
    ) {
        val notificationId = ("call_$callId").hashCode()
        val displayCallerName = if (callerName.isBlank() || callerName.equals("User", ignoreCase = true) || callerName.equals("Chat", ignoreCase = true)) {
            "GoChat Contact"
        } else {
            callerName
        }

        // 1. Answer Intent -> Opens CallActivity
        val answerIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_TARGET_USER_ID, callerId)
            putExtra(CallActivity.EXTRA_PEER_NAME, displayCallerName)
            putExtra(CallActivity.EXTRA_PEER_AVATAR, callerAvatar)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, false)
            putExtra(CallActivity.EXTRA_CALL_TYPE, callType)
        }

        val answerPendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Decline Intent -> NotificationActionReceiver
        val declineIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISMISS_CALL
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
        }

        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callSubtitle = if (callType == "video") "Incoming Video Call" else "Incoming Voice Call"

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(ContextCompat.getColor(context, R.color.gochat_accent))
            .setContentTitle(displayCallerName)
            .setContentText(callSubtitle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(answerPendingIntent)
            .setFullScreenIntent(answerPendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_close, "Decline", declinePendingIntent)
            .addAction(R.drawable.ic_call, "Answer", answerPendingIntent)

        if (avatarBitmap != null) {
            builder.setLargeIcon(avatarBitmap)
        }

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("NotificationHelper", "Missing POST_NOTIFICATIONS permission for call")
                    return
                }
            }
            notificationManager.notify(notificationId, builder.build())
            Log.d("NotificationHelper", "Call notification shown for $callId from $callerName")
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to show call notification", e)
        }
    }
}
