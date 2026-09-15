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
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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

        // 1. Initialize FirebaseApp explicitly (prevents IllegalStateException in all processes)
        initFirebase()

        // 2. Initialize Adaptive DayNight Theme
        ThemeManager.init(this)

        // 3. Register Notification Channels for Android 8.0+
        NotificationHelper.createNotificationChannels(this)

        // 4. Initialize Firebase Cloud Messaging & sync Push Token
        appScope.launch {
            initFcm()
        }

        // 5. Schedule Disappearing Messages Cleanup
        scheduleCleanupWorker()
    }

    /**
     * Initializes FirebaseApp safely, with explicit fallback FirebaseOptions
     * if the auto-init provider is delayed or not ready in this process.
     */
    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.fromResource(this)
                if (options != null) {
                    FirebaseApp.initializeApp(this, options)
                    Log.d(TAG, "FirebaseApp initialized from resource options successfully.")
                } else {
                    FirebaseApp.initializeApp(this)
                    Log.d(TAG, "FirebaseApp initialized from default context successfully.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default FirebaseApp initialization attempt failed, applying fallback FirebaseOptions: ${e.message}")
            try {
                val fallbackOptions = FirebaseOptions.Builder()
                    .setApplicationId("1:652426130187:android:bec3e8dac5e1b315d6680e")
                    .setApiKey("AIzaSyCfyIsDyIm1hm-1LI0x02esZeznm2BOEZk")
                    .setProjectId("gochat-cdba1")
                    .setGcmSenderId("652426130187")
                    .setStorageBucket("gochat-cdba1.firebasestorage.app")
                    .build()
                FirebaseApp.initializeApp(this, fallbackOptions)
                Log.d(TAG, "FirebaseApp fallback initialization successful.")
            } catch (ex: Exception) {
                Log.e(TAG, "Fatal: Unable to initialize FirebaseApp: ${ex.message}", ex)
            }
        }
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
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                initFirebase()
            }

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
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FCM messaging: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "GoChatApp"
    }
}
