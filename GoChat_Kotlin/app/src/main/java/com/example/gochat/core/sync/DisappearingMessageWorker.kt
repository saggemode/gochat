package com.example.gochat.core.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.gochat.data.db.AppDatabase

class DisappearingMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = AppDatabase.getInstance(applicationContext).chatDao()
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
