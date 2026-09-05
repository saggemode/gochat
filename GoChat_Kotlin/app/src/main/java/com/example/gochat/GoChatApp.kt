package com.example.gochat

import android.app.Application
import android.util.Log
import com.example.gochat.core.notification.NotificationHelper
import com.example.gochat.core.theme.ThemeManager
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.repository.AuthRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GoChatApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Adaptive DayNight Theme
        ThemeManager.init(this)

        // 2. Register Notification Channels for Android 8.0+
        NotificationHelper.createNotificationChannels(this)

        // 3. Initialize Firebase Cloud Messaging & sync Push Token
        initFcm()
    }

    private fun initFcm() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            val token = task.result
            if (token.isNullOrBlank()) return@addOnCompleteListener

            Log.d(TAG, "Current FCM Token: $token")
            val tokenManager = TokenManager.getInstance(this)
            tokenManager.fcmToken = token

            if (tokenManager.isLoggedIn) {
                appScope.launch {
                    try {
                        val authRepo = AuthRepository(applicationContext)
                        authRepo.subscribePush(token, "android")
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
