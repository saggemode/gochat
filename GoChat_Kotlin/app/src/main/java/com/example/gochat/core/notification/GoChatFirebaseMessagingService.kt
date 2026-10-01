package com.example.gochat.core.notification

import android.content.Context
import android.os.PowerManager
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
    @Inject lateinit var callRepository: com.example.gochat.data.repository.CallRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        try {
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                com.google.firebase.FirebaseApp.initializeApp(this)
            }
        } catch (_: Exception) {}
    }

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

        // Sanitize body: if it looks like raw JSON, replace with a friendly label
        if (body != null && body.trimStart().startsWith("{")) {
            body = when {
                body.contains("\"type\":\"catalog\"") || body.contains("\"type\": \"catalog\"") -> "🛍️ Product Catalog"
                body.contains("\"product\"") && body.contains("\"price\"") -> "🛒 Product"
                body.contains("\"payment_request\"") || body.contains("\"invoice\"") -> "💳 Payment Request"
                else -> "New message"
            }
        }

        val eventType = data["type"] ?: data["event_type"] ?: ""

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

        // Handle Incoming Call notifications
        if (eventType == "incoming_call" || eventType == "call" || eventType == "call_incoming") {
            val callId = data["call_id"] ?: data["id"] ?: "call_${System.currentTimeMillis()}"
            val callerId = data["caller_id"] ?: data["sender_id"] ?: ""
            val rawCallerName = title
            val callType = data["call_type"] ?: "voice"
            val callerAvatar = data["caller_avatar"] ?: data["sender_avatar"] ?: ""

            val callWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoChat:FCMCallWakeLock")?.apply {
                try { acquire(30000L) } catch (_: Exception) {}
            }

            serviceScope.launch {
                try {
                    val resolvedCallerName = chatRepository.resolveSenderTitle(
                        conversationId = "",
                        senderId = callerId,
                        candidateName = rawCallerName,
                        isGroup = false
                    )

                    // Persist incoming call record in local Room DB
                    val record = com.example.gochat.data.model.CallRecord(
                        id = callId,
                        peerId = callerId,
                        peerName = resolvedCallerName,
                        peerAvatar = callerAvatar,
                        type = if (callType == "video") com.example.gochat.data.model.CallType.VIDEO else com.example.gochat.data.model.CallType.AUDIO,
                        status = com.example.gochat.data.model.CallStatus.INCOMING,
                        durationSeconds = 0,
                        timestamp = System.currentTimeMillis()
                    )
                    callRepository.recordCall(record)

                    NotificationHelper.showCallNotificationAsync(
                        context = applicationContext,
                        callId = callId,
                        callerId = callerId,
                        callerName = resolvedCallerName,
                        callType = callType,
                        callerAvatar = callerAvatar
                    )
                } finally {
                    try {
                        if (callWakeLock?.isHeld == true) callWakeLock.release()
                    } catch (_: Exception) {}
                }
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

        if (eventType == "store_new_product" || eventType == "new_product") {
            val storeId = data["store_id"].orEmpty()
            val storeName = data["store_name"] ?: title
            val productId = data["product_id"].orEmpty()
            val productName = data["product_name"] ?: body
            val price = data["price"].orEmpty()
            val imageUrl = data["image_url"] ?: data["media_url"] ?: ""

            val prodWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoChat:FCMProductWakeLock")?.apply {
                try { acquire(15000L) } catch (_: Exception) {}
            }

            serviceScope.launch {
                try {
                    NotificationHelper.showProductUploadedNotification(
                        context = applicationContext,
                        storeId = storeId,
                        storeName = storeName,
                        productId = productId,
                        productName = productName,
                        price = price,
                        imageUrl = imageUrl
                    )
                } finally {
                    try {
                        if (prodWakeLock?.isHeld == true) prodWakeLock.release()
                    } catch (_: Exception) {}
                }
            }
            return
        }

        if (eventType.startsWith("order_") || eventType == "low_stock") {
            val orderId = data["order_id"] ?: ""
            val productId = data["product_id"] ?: ""

            val orderWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoChat:FCMOrderWakeLock")?.apply {
                try { acquire(15000L) } catch (_: Exception) {}
            }

            serviceScope.launch {
                try {
                    NotificationHelper.showChatNotificationAsync(
                        context = applicationContext,
                        conversationId = if (orderId.isNotEmpty()) "order_$orderId" else "product_$productId",
                        title = title,
                        body = body,
                        senderAvatar = "",
                        isGroup = false
                    )
                } finally {
                    try {
                        if (orderWakeLock?.isHeld == true) orderWakeLock.release()
                    } catch (_: Exception) {}
                }
            }
            return
        }

        // Handle "Added to Group" notifications
        if (eventType == "added_to_group") {
            val groupName = data["group_name"] ?: "a group"
            val groupAvatar = data["group_avatar"] ?: data["sender_avatar"] ?: ""
            val addedByName = data["added_by_name"] ?: "Someone"

            val addedWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoChat:FCMAddedToGroupWakeLock")?.apply {
                try { acquire(15000L) } catch (_: Exception) {}
            }

            serviceScope.launch {
                try {
                    NotificationHelper.showChatNotificationAsync(
                        context = applicationContext,
                        conversationId = conversationId,
                        title = groupName,
                        body = "$addedByName added you to $groupName",
                        senderAvatar = groupAvatar,
                        isGroup = true
                    )
                } finally {
                    try {
                        if (addedWakeLock?.isHeld == true) addedWakeLock.release()
                    } catch (_: Exception) {}
                }
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

        val messageWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoChat:FCMMessageWakeLock")?.apply {
            try { acquire(15000L) } catch (_: Exception) {}
        }

        serviceScope.launch {
            try {
                // Resolve actual user display name / contact name (never "User" placeholder)
                val resolvedTitle = chatRepository.resolveSenderTitle(
                    conversationId = conversationId,
                    senderId = senderId,
                    candidateName = title,
                    isGroup = isGroup
                )

                if (isChatMessage && conversationId.isNotEmpty()) {
                    try {
                        chatRepository.ingestIncomingPushMessage(
                            messageId = messageId,
                            conversationId = conversationId,
                            senderId = senderId,
                            senderName = resolvedTitle,
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

                // 4. Show rich notification with actual peer name / contact name
                NotificationHelper.showChatNotificationAsync(
                    context = applicationContext,
                    conversationId = conversationId,
                    title = resolvedTitle,
                    body = body,
                    senderAvatar = senderAvatar,
                    isGroup = isGroup,
                    isMuted = isMuted
                )
            } finally {
                try {
                    if (messageWakeLock?.isHeld == true) messageWakeLock.release()
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val TAG = "GoChatFCM"
    }
}
