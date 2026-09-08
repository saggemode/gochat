package com.example.gochat.data.api

import android.content.Context
import com.example.gochat.data.websocket.GoChatWebSocket
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json



import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory that constructs the singleton OkHttpClient and Retrofit instances.
 * Centralises timeout, interceptor, and serialisation configuration.
 *
 * Replaces the implicit http.Client setup scattered across Flutter's ApiService.
 */
object NetworkModule {

    /** Lenient JSON parser — ignores unknown keys, coerces nulls to defaults. */
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var apiService: GoChatApiService? = null

    @Volatile
    private var ktorClient: HttpClient? = null

    @Volatile
    private var webSocket: GoChatWebSocket? = null

    /**
     * Returns the shared Ktor HttpClient.
     */
    fun getKtorHttpClient(): HttpClient {
        return ktorClient ?: synchronized(this) {
            ktorClient ?: buildKtorHttpClient().also { ktorClient = it }
        }
    }

    /**
     * Returns the shared OkHttpClient configured with auth injection and logging.
     */
    fun getOkHttpClient(context: Context): OkHttpClient {
        return okHttpClient ?: synchronized(this) {
            okHttpClient ?: buildOkHttpClient(context).also { okHttpClient = it }
        }
    }

    /**
     * Returns the shared Retrofit instance.
     */
    fun getRetrofit(context: Context): Retrofit {
        return retrofit ?: synchronized(this) {
            retrofit ?: buildRetrofit(context).also { retrofit = it }
        }
    }

    /**
     * Returns the shared GoChatWebSocket instance.
     */
    fun getWebSocket(): GoChatWebSocket {
        return webSocket ?: synchronized(this) {
            webSocket ?: GoChatWebSocket(
                getKtorHttpClient(),
                json
            ).also { webSocket = it }
        }
    }



    /**
     * Returns the shared GoChatApiService.
     */
    fun getApiService(context: Context): GoChatApiService {
        return apiService ?: synchronized(this) {
            apiService ?: getRetrofit(context)
                .create(GoChatApiService::class.java)
                .also { apiService = it }
        }
    }

    // ── Private Builders ─────────────────────────────────────────

    private fun buildKtorHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(WebSockets) {
                pingInterval = 20_000
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(Logging) {
                level = LogLevel.BODY
            }
            // Manual injection for non-DI entry points
            defaultRequest {
                // We'd need a context here for TokenManager.getInstance(context)
                // but since this is used sparingly, we can add it when needed.
            }
        }
    }



    private fun buildOkHttpClient(context: Context): OkHttpClient {
        val tokenManager = TokenManager.getInstance(context)
        val authInterceptor = AuthInterceptor(tokenManager)

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)    // generous for media uploads
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun buildRetrofit(context: Context): Retrofit {
        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl(ApiConstants.BASE_URL + "/")
            .client(getOkHttpClient(context))
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}
