package com.example.gochat.core.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupManager: ChatBackupManager,
    private val driveManager: GoogleDriveBackupManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting automated background chat backup...")

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val frequency = prefs.getString(KEY_FREQUENCY, "Daily") ?: "Daily"

        if (frequency.equals("Off", ignoreCase = true)) {
            Log.d(TAG, "Auto-backup frequency is set to Off. Skipping.")
            return Result.success()
        }

        val password = prefs.getString(KEY_PASSWORD, null)
        if (password.isNullOrBlank()) {
            Log.w(TAG, "No master password configured for auto-backup. Cannot encrypt.")
            return Result.success()
        }

        val includeMedia = prefs.getBoolean(KEY_INCLUDE_MEDIA, true)

        return try {
            // 1. Create local encrypted backup container
            val result = backupManager.createEncryptedBackup(
                password = password,
                includeMedia = includeMedia
            )

            result.onSuccess { meta ->
                Log.d(TAG, "Local encrypted backup generated: ${meta.filePath} (${meta.formattedSize})")

                // 2. Upload to Google Drive if account is connected
                val account = driveManager.getSignedInAccount()
                if (account != null) {
                    val file = File(meta.filePath)
                    val uploadResult = driveManager.uploadBackupFile(account, file)
                    uploadResult.onSuccess { driveFileId ->
                        val cloudMeta = meta.copy(
                            isCloudBackup = true,
                            cloudFileId = driveFileId,
                            accountEmail = account.email
                        )
                        backupManager.saveLastBackupInfo(cloudMeta)
                        Log.d(TAG, "Auto-backup successfully synced to Google Drive (ID: $driveFileId)")
                    }.onFailure { err ->
                        Log.w(TAG, "Google Drive upload failed during auto-backup: ${err.message}")
                    }
                } else {
                    Log.d(TAG, "No Google Drive account connected; backup stored locally.")
                }
            }.onFailure { err ->
                Log.e(TAG, "Failed to create encrypted backup container", err)
                return Result.retry()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in AutoBackupWorker", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val WORK_NAME = "gochat_auto_backup_worker"
        const val PREFS_NAME = "gochat_backup_settings"
        const val KEY_FREQUENCY = "auto_backup_freq"
        const val KEY_PASSWORD = "auto_backup_password"
        const val KEY_INCLUDE_MEDIA = "include_media"

        fun updateSchedule(context: Context, frequency: String) {
            val workManager = WorkManager.getInstance(context)

            if (frequency.equals("Off", ignoreCase = true)) {
                workManager.cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Auto-backup cancelled.")
                return
            }

            val intervalHours: Long = when (frequency.lowercase()) {
                "daily" -> 24
                "weekly" -> 24 * 7
                "monthly" -> 24 * 30
                else -> 24
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                intervalHours, TimeUnit.HOURS,
                1, TimeUnit.HOURS // 1h flex window
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Auto-backup scheduled with frequency: $frequency (every $intervalHours hrs)")
        }
    }
}
