package com.example.gochat.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that injects the JWT `Authorization: Bearer <token>` header
 * into every outgoing request (except auth endpoints that don't require it).
 *
 * Replaces the Flutter `_headers(requireAuth: true)` pattern.
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {

    companion object {
        /** Paths that must NOT receive the Authorization header. */
        private val PUBLIC_PATHS = setOf(
            ApiConstants.REGISTER,
            ApiConstants.LOGIN,
            ApiConstants.REQUEST_OTP,
            ApiConstants.VERIFY_OTP,
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath.removePrefix("/")

        // Skip auth header for public endpoints
        if (PUBLIC_PATHS.any { path.startsWith(it) }) {
            return chain.proceed(originalRequest)
        }

        val token = tokenManager.getToken()
        if (token.isNullOrBlank() || !tokenManager.isValidJwt(token)) {
            return chain.proceed(originalRequest)
        }

        val authedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authedRequest)
    }
}
