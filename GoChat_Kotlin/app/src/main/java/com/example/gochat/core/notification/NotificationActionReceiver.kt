package com.example.gochat.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.gochat.data.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
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

    @Inject
    lateinit var callRepository: com.example.gochat.data.repository.CallRepository

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
                val senderTitle = intent.getStringExtra(NotificationHelper.EXTRA_CONVERSATION_TITLE) ?: "GoChat Contact"

                if (!replyText.isNullOrBlank() && conversationId.isNotBlank()) {
                    // 1. Immediately update the notification to dismiss system RemoteInput progress spinner
                    NotificationHelper.updateNotificationWithReply(
                        context = context,
                        conversationId = conversationId,
                        notificationId = notificationId,
                        replyText = replyText,
                        senderTitle = senderTitle
                    )

                    // 2. Attempt immediate dispatch; only fallback to WorkManager if immediate send fails
                    scope.launch {
                        try {
                            Log.d(TAG, "Attempting direct reply to $conversationId: $replyText")
                            val sendResult = chatRepository.sendMessage(
                                conversationId = conversationId,
                                content = replyText
                            )
                            if (sendResult.isSuccess) {
                                chatRepository.markConversationAsRead(conversationId)
                            } else {
                                Log.w(TAG, "Direct reply failed, enqueuing WorkManager fallback: ${sendResult.exceptionOrNull()?.message}")
                                enqueueDirectReplyWork(context, conversationId, replyText, notificationId)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Direct reply threw exception, enqueuing WorkManager fallback: ${e.message}")
                            enqueueDirectReplyWork(context, conversationId, replyText, notificationId)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            NotificationHelper.ACTION_MARK_AS_READ -> {
                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.cancel(notificationId)

                if (conversationId.isNotBlank()) {
                    scope.launch {
                        try {
                            Log.d(TAG, "Marking conversation as read: $conversationId")
                            chatRepository.markConversationAsRead(conversationId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to mark as read", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            NotificationHelper.ACTION_DISMISS_CALL -> {
                val callId = intent.getStringExtra(com.example.gochat.ui.calls.CallActivity.EXTRA_CALL_ID) ?: ""
                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.cancel(notificationId)

                if (callId.isNotBlank()) {
                    scope.launch {
                        try {
                            callRepository.rejectCall(callId)
                            callRepository.updateCallStatus(callId, com.example.gochat.data.model.CallStatus.MISSED, 0)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decline call $callId", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    pendingResult.finish()
                }
            }

            else -> {
                pendingResult.finish()
            }
        }
    }

    private fun enqueueDirectReplyWork(
        context: Context,
        conversationId: String,
        replyText: String,
        notificationId: Int
    ) {
        val workData = workDataOf(
            DirectReplyWorker.KEY_CONVERSATION_ID to conversationId,
            DirectReplyWorker.KEY_REPLY_TEXT to replyText,
            DirectReplyWorker.KEY_NOTIFICATION_ID to notificationId
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val replyWorkRequest = OneTimeWorkRequestBuilder<DirectReplyWorker>()
            .setInputData(workData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        try {
            WorkManager.getInstance(context).enqueue(replyWorkRequest)
        } catch (we: Exception) {
            Log.w(TAG, "Failed to enqueue DirectReplyWorker: ${we.message}")
        }
    }

    companion object {
        private const val TAG = "NotifActionReceiver"
    }
}
