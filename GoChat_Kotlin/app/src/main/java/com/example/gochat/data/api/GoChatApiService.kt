package com.example.gochat.data.api

import com.example.gochat.data.model.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit 2 interface defining all GoChat Gateway REST endpoints.
 * Mirrors every endpoint from Flutter's `ApiService` class.
 */
interface GoChatApiService {

    // ═══════════════════════════════════════════════════════════════
    // ── Auth ─────────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @POST(ApiConstants.REGISTER)
    suspend fun register(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST(ApiConstants.LOGIN)
    suspend fun login(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST(ApiConstants.REQUEST_OTP)
    suspend fun requestOtp(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST(ApiConstants.VERIFY_OTP)
    suspend fun verifyOtp(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST(ApiConstants.REFRESH)
    suspend fun refreshToken(
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET("api/v1/users/{pin}")
    suspend fun lookupUserByPin(
        @Path("pin") pin: String
    ): Response<JsonObject>

    @PATCH(ApiConstants.USER_PROFILE)
    suspend fun updateProfile(
        @Body body: JsonObject
    ): Response<JsonObject>

    // ═══════════════════════════════════════════════════════════════
    // ── Contacts ─────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @POST(ApiConstants.SYNC_CONTACTS)
    suspend fun syncContacts(
        @Body body: JsonObject
    ): Response<JsonElement>

    // ═══════════════════════════════════════════════════════════════
    // ── Conversations ────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @GET(ApiConstants.CONVERSATIONS)
    suspend fun getConversations(): Response<JsonElement>

    @POST(ApiConstants.CONVERSATIONS)
    suspend fun createConversation(
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET("api/v1/chat/conversations/{id}/messages")
    suspend fun getMessages(
        @Path("id") convId: String
    ): Response<JsonElement>

    @POST("api/v1/chat/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") convId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    // ═══════════════════════════════════════════════════════════════
    // ── Polls ────────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @POST("api/v1/chat/polls/{pollId}/vote")
    suspend fun votePoll(
        @Path("pollId") pollId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    // ═══════════════════════════════════════════════════════════════
    // ── Stories / Status ─────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @GET(ApiConstants.STORIES)
    suspend fun getStories(): Response<JsonElement>

    @POST(ApiConstants.STORIES)
    suspend fun postStory(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST("api/v1/stories/{id}/view")
    suspend fun viewStory(
        @Path("id") storyId: String
    ): Response<JsonObject>

    @GET("api/v1/stories/{id}/viewers")
    suspend fun getStoryViewers(
        @Path("id") storyId: String
    ): Response<JsonElement>

    // ═══════════════════════════════════════════════════════════════
    // ── Calls ────────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @POST(ApiConstants.CALLS)
    suspend fun startCall(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST("api/v1/calls/{id}/accept")
    suspend fun acceptCall(
        @Path("id") callId: String
    ): Response<JsonObject>

    @POST("api/v1/calls/{id}/reject")
    suspend fun rejectCall(
        @Path("id") callId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST("api/v1/calls/{id}/end")
    suspend fun endCall(
        @Path("id") callId: String
    ): Response<JsonObject>

    @POST("api/v1/calls/{id}/signaling")
    suspend fun sendSignaling(
        @Path("id") callId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET(ApiConstants.CALL_HISTORY)
    suspend fun getCallHistory(): Response<JsonElement>

    // ═══════════════════════════════════════════════════════════════
    // ── Channels ─────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @GET(ApiConstants.CHANNELS)
    suspend fun getChannels(): Response<JsonElement>

    @GET("api/v1/channels/{id}/feed")
    suspend fun getChannelFeed(
        @Path("id") channelId: String
    ): Response<JsonElement>

    // ═══════════════════════════════════════════════════════════════
    // ── Marketplace & Business ───────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @GET(ApiConstants.PRODUCTS)
    suspend fun getProducts(): Response<JsonElement>

    @GET(ApiConstants.BUSINESS_PROFILE)
    suspend fun getBusinessProfile(): Response<JsonObject>

    @POST(ApiConstants.BUSINESS_PROFILE)
    suspend fun createBusinessProfile(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST(ApiConstants.BUSINESS_PRODUCTS)
    suspend fun createProduct(
        @Body body: JsonObject
    ): Response<JsonObject>

    // ═══════════════════════════════════════════════════════════════
    // ── Media Upload ─────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @Multipart
    @POST(ApiConstants.MEDIA_UPLOAD)
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part
    ): Response<JsonObject>

    @GET("api/v1/media/download/{fileId}")
    suspend fun downloadMedia(
        @Path("fileId") fileId: String
    ): Response<okhttp3.ResponseBody>
}
