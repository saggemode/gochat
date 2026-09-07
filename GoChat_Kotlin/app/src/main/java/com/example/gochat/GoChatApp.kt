package com.example.gochat

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.core.theme.ThemeManager
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.core.sync.DisappearingMessageWorker
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class GoChatApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Adaptive DayNight Theme
        ThemeManager.init(this)

        // 2. Register Notification Channels for Android 8.0+
        NotificationHelper.createNotificationChannels(this)

        // 3. Initialize Firebase Cloud Messaging & sync Push Token
        appScope.launch {
            initFcm()
        }

        // 4. Schedule Disappearing Messages Cleanup
        scheduleCleanupWorker()
    }

    private fun scheduleCleanupWorker() {
        val cleanupRequest = PeriodicWorkRequestBuilder<DisappearingMessageWorker>(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "disappearing_messages_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )
    }

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var tokenManager: TokenManager

    private fun initFcm() {
        FirebaseMessaging.getInstance().register().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "FCM registration failed", task.exception)
                return@addOnCompleteListener
            }

            Log.d(TAG, "FCM registration triggered successfully.")

            // In V1, the token (FID) is delivered to GoChatFirebaseMessagingService.onRegistered.
            // We can also retrieve it here using FirebaseInstallations.
            FirebaseInstallations.getInstance().id.addOnSuccessListener { fid ->
                if (fid.isNullOrBlank()) return@addOnSuccessListener

                Log.d(TAG, "Current FCM Token (FID): $fid")
                tokenManager.fcmToken = fid

                if (tokenManager.isLoggedIn) {
                    appScope.launch {
                        try {
                            authRepository.subscribePush(fid, "android")
                            Log.d(TAG, "FCM push token registered with GoChat gateway backend.")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to register FCM token with backend: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GoChatApp"
    }
}
