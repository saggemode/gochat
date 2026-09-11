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
        handleRegistration(token, "onNewToken")
    }

    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        handleRegistration(installationId, "onRegistered")
    }

    override fun onUnregistered(installationId: String) {
        super.onUnregistered(installationId)
        Log.d(TAG, "FCM Unregistered: $installationId")
    }

    private fun handleRegistration(token: String, source: String) {
        Log.d(TAG, "FCM Token received ($source): $token")

        serviceScope.launch {
            tokenManager.fcmToken = token

            if (tokenManager.isLoggedIn) {
                try {
                    authRepository.subscribePush(token, "android")
                    Log.d(TAG, "Token from $source registered with backend successfully.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register token from $source with backend", e)
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // No need to call super.onMessageReceived()
        Log.d(TAG, "FCM message received from=${remoteMessage.from}")
        Log.d(TAG, "FCM Message Data: ${remoteMessage.data}")
        Log.d(TAG, "FCM Message Notification: ${remoteMessage.notification?.body}")

        val data = remoteMessage.data
        
        // Priority 1: Data payload (for custom handling and deep-linking)
        var title = data["title"] ?: data["sender_name"] ?: data["senderName"]
        var body = data["body"] ?: data["content"] ?: data["message"] ?: data["text"]
        
        // Priority 2: Fallback to system notification payload if data is missing
        if (title == null) title = remoteMessage.notification?.title
        if (body == null) body = remoteMessage.notification?.body
        
        // Defaults if all else fails
        if (title == null) title = "GoChat Message"
        if (body == null) body = "You received a new message"

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

        val eventType = data["type"] ?: data["event_type"] ?: ""
        if (eventType.startsWith("order_") || eventType == "low_stock") {
            val orderId = data["order_id"] ?: ""
            val productId = data["product_id"] ?: ""
            
            NotificationHelper.showChatNotification(
                context = applicationContext,
                conversationId = if (orderId.isNotEmpty()) "order_$orderId" else "product_$productId",
                title = title,
                body = body,
                senderAvatar = "",
                isGroup = false
            )
            return
        }


        // 1. WhatsApp-Style Background Ingestion: Persist message into Room DB immediately
        // so that even if the app was closed, the message is already saved and available offline.
        val isChatMessage = eventType == "chat_message" ||
                eventType == "new_message" ||
                eventType == "message" ||
                conversationId.isNotEmpty()

        if (isChatMessage && conversationId.isNotEmpty()) {
            val messageId = data["message_id"] ?: data["id"] ?: "msg_${System.currentTimeMillis()}"
            val senderId = data["sender_id"] ?: data["senderId"] ?: ""
            val senderName = title
            val content = body
            val mediaUrl = data["media_url"] ?: data["mediaUrl"]
            val rawType = data["message_type"] ?: data["type_int"]
            val msgTypeInt = rawType?.toIntOrNull() ?: 0

            serviceScope.launch {
                try {
                    chatRepository.ingestIncomingPushMessage(
                        messageId = messageId,
                        conversationId = conversationId,
                        senderId = senderId,
                        senderName = senderName,
                        content = content,
                        mediaUrl = mediaUrl,
                        type = msgTypeInt
                    )
                    Log.d(TAG, "Ingested push message $messageId into Room DB for conversation $conversationId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to ingest push message into Room DB: ${e.message}", e)
                }
            }
        }

        // 2. Suppression check: If user is currently actively viewing this conversation, suppress the notification banner
        val activeConv = ChatRepository.activeConversationIdStatic
        if (conversationId.isNotEmpty() && activeConv == conversationId) {
            Log.d(TAG, "Suppressing notification: user is active in conversation $conversationId")
            return
        }

        NotificationHelper.showChatNotification(
            context = applicationContext,
            conversationId = conversationId,
            title = title,
            body = body,
            senderAvatar = senderAvatar,
            isGroup = isGroup
        )
    }

    companion object {
        private const val TAG = "GoChatFCM"
    }
}
