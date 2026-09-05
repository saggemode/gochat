package com.example.gochat.core.notification

import android.util.Log
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.data.repository.ChatRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GoChatFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")

        val tokenManager = TokenManager.getInstance(applicationContext)
        tokenManager.fcmToken = token

        if (tokenManager.isLoggedIn) {
            val authRepository = AuthRepository(applicationContext)
            serviceScope.launch {
                authRepository.subscribePush(token, "android")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from=${remoteMessage.from}, data=${remoteMessage.data}")

        val data = remoteMessage.data
        val title = data["title"]
            ?: data["sender_name"]
            ?: remoteMessage.notification?.title
            ?: "GoChat Message"

        val body = data["body"]
            ?: data["content"]
            ?: data["message"]
            ?: remoteMessage.notification?.body
            ?: "You received a new message"

        val conversationId = data["conversation_id"]
            ?: data["conv_id"]
            ?: data["conversationId"]
            ?: ""

        val senderAvatar = data["sender_avatar"]
            ?: data["avatar_url"]
            ?: ""

        val isGroup = data["is_group"]?.toBoolean() ?: false

        // Don't show notification if the user is currently looking at this active conversation
        if (conversationId.isNotEmpty() && ChatRepository(applicationContext).activeConversationId == conversationId) {
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
