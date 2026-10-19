package com.example.gochat.core.contacts

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.data.api.TokenManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class ContactSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val contactSyncManager: ContactSyncManager,
    private val tokenManager: TokenManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic contact sync...")

        if (!tokenManager.isLoggedIn) {
            Log.d(TAG, "User not logged in; skipping background contact sync.")
            return Result.success()
        }

        if (!contactSyncManager.hasPermission()) {
            Log.d(TAG, "READ_CONTACTS permission not granted; skipping background contact sync.")
            return Result.success()
        }

        return try {
            val newlyDiscovered = contactSyncManager.performBackgroundSyncAndDetectNew()
            Log.d(TAG, "Periodic contact sync complete. Newly discovered contacts: ${newlyDiscovered.size}")

            if (newlyDiscovered.isNotEmpty()) {
                if (newlyDiscovered.size == 1) {
                    NotificationHelper.showNewContactNotificationAsync(appContext, newlyDiscovered.first())
                } else {
                    NotificationHelper.showMultipleNewContactsNotification(appContext, newlyDiscovered)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run background contact sync", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ContactSyncWorker"
        const val PERIODIC_WORK_NAME = "gochat_periodic_contact_sync"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Run periodic sync every 6 hours with a 30-minute flex window
            val syncRequest = PeriodicWorkRequestBuilder<ContactSyncWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "Periodic contact sync worker registered.")
        }

        fun syncOnceImmediately(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val singleRequest = OneTimeWorkRequestBuilder<ContactSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(singleRequest)
            Log.d(TAG, "One-time immediate contact sync enqueued.")
        }
    }
}
