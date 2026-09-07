package com.example.gochat.di

import android.content.Context
import com.example.gochat.data.api.ApiConstants
import com.example.gochat.data.api.AuthInterceptor
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.websocket.GoChatWebSocket
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            encodeDefaults = true
        }
    }

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager {
        return TokenManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        tokenManager: TokenManager
    ): OkHttpClient {
        val authInterceptor = AuthInterceptor(tokenManager)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(ApiConstants.BASE_URL + "/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideGoChatApiService(retrofit: Retrofit): GoChatApiService {
        return retrofit.create(GoChatApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGoChatWebSocket(okHttpClient: OkHttpClient, json: Json): GoChatWebSocket {
        return GoChatWebSocket(okHttpClient, json)
    }
}
