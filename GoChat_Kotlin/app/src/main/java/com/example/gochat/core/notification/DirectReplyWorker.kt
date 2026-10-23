package com.example.gochat.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.gochat.R
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
        private const val FG_CHANNEL_ID = "gochat_reply_sending"
        private const val FG_NOTIFICATION_ID = 920001
    }

    /**
     * Required for expedited work on API < 31 (where WorkManager runs it as a short-lived
     * foreground service). On API 31+ expedited jobs don't use this.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                FG_CHANNEL_ID,
                "Sending replies",
                NotificationManager.IMPORTANCE_MIN
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, FG_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle("Sending reply…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FG_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FG_NOTIFICATION_ID, notification)
        }
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
