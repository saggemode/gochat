package com.example.gochat.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.gochat.data.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles interactive notification actions:
 * - Direct Reply via RemoteInput
 * - Mark as Read
 * - Dismiss / Decline Call
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var chatRepository: ChatRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val conversationId = intent.getStringExtra(NotificationHelper.EXTRA_CONVERSATION_ID) ?: ""
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, conversationId.hashCode())

        Log.d(TAG, "onReceive: action=$action, convId=$conversationId, notifId=$notificationId")

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)

        when (action) {
            NotificationHelper.ACTION_DIRECT_REPLY -> {
                val resultsBundle = RemoteInput.getResultsFromIntent(intent)
                val replyText = resultsBundle?.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)?.toString()

                if (!replyText.isNullOrBlank() && conversationId.isNotBlank()) {
                    scope.launch {
                        try {
                            Log.d(TAG, "Sending direct reply to $conversationId: $replyText")
                            chatRepository.sendMessage(
                                conversationId = conversationId,
                                content = replyText
                            )
                            chatRepository.markConversationAsRead(conversationId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send direct reply", e)
                        } finally {
                            val notificationManager = NotificationManagerCompat.from(context)
                            notificationManager.cancel(notificationId)
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            NotificationHelper.ACTION_MARK_AS_READ -> {
                if (conversationId.isNotBlank()) {
                    scope.launch {
                        try {
                            Log.d(TAG, "Marking conversation as read: $conversationId")
                            chatRepository.markConversationAsRead(conversationId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to mark as read", e)
                        } finally {
                            val notificationManager = NotificationManagerCompat.from(context)
                            notificationManager.cancel(notificationId)
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            NotificationHelper.ACTION_DISMISS_CALL -> {
                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.cancel(notificationId)
                pendingResult.finish()
            }

            else -> {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NotifActionReceiver"
    }
}
