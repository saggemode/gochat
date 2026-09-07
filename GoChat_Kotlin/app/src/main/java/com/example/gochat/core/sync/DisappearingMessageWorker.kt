package com.example.gochat.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.gochat.data.db.ChatDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DisappearingMessageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: ChatDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val deletedCount = dao.deleteExpiredMessages(System.currentTimeMillis())
            if (deletedCount > 0) {
                Log.d("DisappearingWorker", "Deleted $deletedCount expired messages")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("DisappearingWorker", "Failed to delete expired messages: ${e.message}")
            Result.retry()
        }
    }
}
