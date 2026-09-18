package com.example.gochat.data.api

import android.util.Log
import com.example.gochat.data.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Transparent OkHttp authenticator that intercepts 401 Unauthorized responses,
 * rotates the expired JWT token via [AuthRepository.ensureValidToken], and automatically
 * retries the failed request with the new access token.
 *
 * Implements WhatsApp / Telegram-grade session persistence.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val tokenManager: TokenManager
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRY_COUNT = 3
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_RETRY_COUNT) {
            Log.w(TAG, "Exceeded maximum auth retry attempts (${MAX_RETRY_COUNT}); aborting")
            return null
        }

        val originalHeader = response.request.header("Authorization")
        Log.d(TAG, "Received 401 Unauthorized for ${response.request.url}. Attempting token rotation...")

        synchronized(this) {
            // Check if another thread has already refreshed the token
            val currentToken = tokenManager.getToken()
            if (!currentToken.isNullOrBlank() && "Bearer $currentToken" != originalHeader && !tokenManager.isTokenExpired(currentToken)) {
                Log.d(TAG, "Token already rotated by concurrent thread; retrying request")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            // Perform token refresh or auto-login
            val freshToken = try {
                runBlocking {
                    authRepositoryProvider.get().ensureValidToken()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh token in Authenticator: ${e.message}", e)
                null
            }

            if (!freshToken.isNullOrBlank() && "Bearer $freshToken" != originalHeader) {
                Log.i(TAG, "Token successfully rotated. Retrying request with new token.")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $freshToken")
                    .build()
            }
        }

        Log.w(TAG, "Token rotation failed or returned same token; cannot authenticate request")
        return null
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
