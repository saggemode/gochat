package com.example.gochat.core.notification

import android.util.Log
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GoChatFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var chatRepository: ChatRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM onNewToken received: $token")

        serviceScope.launch {
            tokenManager.fcmToken = token

            if (tokenManager.isLoggedIn) {
                try {
                    authRepository.subscribePush(token, "android")
                    Log.d(TAG, "FCM registration token synced with backend successfully.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register FCM token with backend", e)
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "FCM message received from=${remoteMessage.from}")
        Log.d(TAG, "FCM Message Data: ${remoteMessage.data}")

        val data = remoteMessage.data

        // Priority 1: Data payload
        var title = data["title"] ?: data["sender_name"] ?: data["senderName"]
        var body = data["body"] ?: data["content"] ?: data["message"] ?: data["text"]

        // Priority 2: Fallback to system notification payload if data is missing
        if (title == null) title = remoteMessage.notification?.title
        if (body == null) body = remoteMessage.notification?.body

        if (title == null) title = "GoChat Message"
        if (body == null) body = "You received a new message"

        val eventType = data["type"] ?: data["event_type"] ?: ""

        // Handle Incoming Call notifications
        if (eventType == "incoming_call" || eventType == "call" || eventType == "call_incoming") {
            val callId = data["call_id"] ?: data["id"] ?: "call_${System.currentTimeMillis()}"
            val callerId = data["caller_id"] ?: data["sender_id"] ?: ""
            val callerName = title
            val callType = data["call_type"] ?: "voice"
            val callerAvatar = data["caller_avatar"] ?: data["sender_avatar"] ?: ""

            serviceScope.launch {
                NotificationHelper.showCallNotificationAsync(
                    context = applicationContext,
                    callId = callId,
                    callerId = callerId,
                    callerName = callerName,
                    callType = callType,
                    callerAvatar = callerAvatar
                )
            }
            return
        }

        val conversationId = data["conversation_id"]
            ?: data["conv_id"]
            ?: data["conversationId"]
            ?: ""

        val senderAvatar = data["sender_avatar"]
            ?: data["avatar_url"]
            ?: data["avatarUrl"]
            ?: ""

        val isGroup = data["is_group"]?.toBoolean()
            ?: data["isGroup"]?.toBoolean()
            ?: false

        if (eventType.startsWith("order_") || eventType == "low_stock") {
            val orderId = data["order_id"] ?: ""
            val productId = data["product_id"] ?: ""

            serviceScope.launch {
                NotificationHelper.showChatNotificationAsync(
                    context = applicationContext,
                    conversationId = if (orderId.isNotEmpty()) "order_$orderId" else "product_$productId",
                    title = title,
                    body = body,
                    senderAvatar = "",
                    isGroup = false
                )
            }
            return
        }

        // 1. WhatsApp-Style Background Ingestion: Persist message into Room DB immediately
        val isChatMessage = eventType == "chat_message" ||
                eventType == "new_message" ||
                eventType == "message" ||
                conversationId.isNotEmpty()

        val messageId = data["message_id"] ?: data["id"] ?: "msg_${System.currentTimeMillis()}"
        val senderId = data["sender_id"] ?: data["senderId"] ?: ""
        val mediaUrl = data["media_url"] ?: data["mediaUrl"]
        val rawType = data["message_type"] ?: data["type_int"]
        val msgTypeInt = rawType?.toIntOrNull() ?: 0

        serviceScope.launch {
            if (isChatMessage && conversationId.isNotEmpty()) {
                try {
                    chatRepository.ingestIncomingPushMessage(
                        messageId = messageId,
                        conversationId = conversationId,
                        senderId = senderId,
                        senderName = title,
                        content = body,
                        mediaUrl = mediaUrl,
                        type = msgTypeInt
                    )
                    Log.d(TAG, "Ingested push message $messageId into Room DB for conversation $conversationId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to ingest push message into Room DB: ${e.message}", e)
                }
            }

            // 2. Active Chat Suppression check
            val activeConv = ChatRepository.activeConversationIdStatic
            if (conversationId.isNotEmpty() && activeConv == conversationId) {
                Log.d(TAG, "Suppressing notification: user is active in conversation $conversationId")
                return@launch
            }

            // 3. Mute check
            val conv = if (conversationId.isNotEmpty()) chatRepository.getConversationById(conversationId) else null
            val isMuted = conv?.isMuted ?: false

            // 4. Show rich notification
            NotificationHelper.showChatNotificationAsync(
                context = applicationContext,
                conversationId = conversationId,
                title = title,
                body = body,
                senderAvatar = senderAvatar,
                isGroup = isGroup,
                isMuted = isMuted
            )
        }
    }

    companion object {
        private const val TAG = "GoChatFCM"
    }
}
