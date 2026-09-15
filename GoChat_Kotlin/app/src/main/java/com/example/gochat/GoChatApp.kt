package com.example.gochat

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.core.theme.ThemeManager
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.example.gochat.core.sync.DisappearingMessageWorker
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class GoChatApp : Application(), Configuration.Provider, ImageLoaderFactory {

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

    /**
     * Provides a Coil ImageLoader that uses the app's authenticated OkHttpClient.
     * This ensures all image loads (chat media, avatars, etc.) include the
     * auth bearer token required by the /api/v1/media/download/ endpoint.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(NetworkModule.getOkHttpClient(this))
            .crossfade(true)
            .build()
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
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            val token = task.result
            if (token.isNullOrBlank()) {
                Log.w(TAG, "FCM registration token is null or blank")
                return@addOnCompleteListener
            }

            Log.d(TAG, "Current FCM Token: $token")
            tokenManager.fcmToken = token

            if (tokenManager.isLoggedIn) {
                appScope.launch {
                    try {
                        authRepository.subscribePush(token, "android")
                        Log.d(TAG, "FCM push token registered with GoChat gateway backend.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to register FCM token with backend: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GoChatApp"
    }
}
