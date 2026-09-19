package com.example.gochat.core.notification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.gochat.data.repository.ChatRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background WorkManager worker responsible for reliably sending inline direct replies
 * even if the app process is terminated or the network connection drops momentarily.
 */
@HiltWorker
class DirectReplyWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val chatRepository: ChatRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "DirectReplyWorker"
        const val KEY_CONVERSATION_ID = "key_conversation_id"
        const val KEY_REPLY_TEXT = "key_reply_text"
        const val KEY_NOTIFICATION_ID = "key_notification_id"
    }

    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: return Result.failure()
        val replyText = inputData.getString(KEY_REPLY_TEXT) ?: return Result.failure()
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, -1)

        Log.d(TAG, "DirectReplyWorker executing for conv=$conversationId, notifId=$notificationId")

        return try {
            val sendResult = chatRepository.sendMessage(
                conversationId = conversationId,
                content = replyText
            )

            // Mark conversation as read since user actively responded to it
            try {
                chatRepository.markConversationAsRead(conversationId)
            } catch (_: Exception) {}

            if (sendResult.isSuccess) {
                Log.d(TAG, "Direct reply dispatched successfully for conv=$conversationId")
                Result.success()
            } else {
                val error = sendResult.exceptionOrNull()
                Log.w(TAG, "Direct reply failed with: ${error?.message}, attempt=$runAttemptCount")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during direct reply delivery: ${e.message}, attempt=$runAttemptCount", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
