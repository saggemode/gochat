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

    @POST(ApiConstants.SUBSCRIBE_PUSH)
    suspend fun subscribePush(
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET("api/v1/users/privacy")
    suspend fun getPrivacySettings(): Response<JsonObject>

    @PUT("api/v1/users/privacy")
    suspend fun updatePrivacySettings(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST("api/v1/auth/e2ee/keys")
    suspend fun uploadE2EEKeys(
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET("api/v1/auth/e2ee/keys/{user_id}")
    suspend fun getE2EEKeys(
        @Path("user_id") userId: String
    ): Response<JsonObject>

    // ── Linked Devices ───────────────────────────────────────────
    @GET("api/v1/auth/devices")
    suspend fun getLinkedDevices(): Response<JsonObject>

    @POST("api/v1/auth/devices")
    suspend fun registerDevice(
        @Body body: JsonObject
    ): Response<JsonObject>

    @DELETE("api/v1/auth/devices/{id}")
    suspend fun unlinkDevice(
        @Path("id") deviceId: String
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

    @POST("api/v1/chat/conversations/{id}/members")
    suspend fun addMember(
        @Path("id") convId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @HTTP(method = "DELETE", path = "api/v1/chat/conversations/{id}/members", hasBody = true)
    suspend fun removeMember(
        @Path("id") convId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET("api/v1/chat/conversations/{id}/messages")
    suspend fun getMessages(
        @Path("id") convId: String,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("before") before: String? = null
    ): Response<JsonElement>

    @POST("api/v1/chat/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") convId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    // ═══════════════════════════════════════════════════════════════
    // ── Group Management ─────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    @GET("api/v1/groups/{id}/metadata")
    suspend fun getGroupMetadata(
        @Path("id") convId: String
    ): Response<GroupMetadata>

    @POST("api/v1/groups/{id}/metadata")
    suspend fun updateGroupMetadata(
        @Path("id") convId: String,
        @Body body: JsonObject
    ): Response<GroupMetadata>

    @POST("api/v1/groups/{id}/invite-link")
    suspend fun generateInviteLink(
        @Path("id") convId: String
    ): Response<JsonObject>

    @POST("api/v1/groups/join")
    suspend fun joinByInviteCode(
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST("api/v1/groups/{id}/members/{userId}/promote")
    suspend fun promoteMember(
        @Path("id") convId: String,
        @Path("userId") userId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST("api/v1/groups/{id}/members/{userId}/demote")
    suspend fun demoteMember(
        @Path("id") convId: String,
        @Path("userId") userId: String
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
    suspend fun getProducts(
        @Query("category_id") categoryId: String? = null,
        @Query("search") search: String? = null,
        @Query("sort_by") sortBy: String? = null,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null
    ): Response<JsonElement>

    @GET(ApiConstants.CATEGORIES)
    suspend fun getCategories(): Response<JsonElement>

    @GET("api/v1/marketplace/stores/{id}")
    suspend fun getStore(
        @Path("id") storeId: String
    ): Response<JsonObject>

    @GET("api/v1/marketplace/stores/{id}/products")
    suspend fun getStoreProducts(
        @Path("id") storeId: String
    ): Response<JsonElement>

    @GET(ApiConstants.CART)
    suspend fun getCart(): Response<JsonElement>

    @POST(ApiConstants.CART)
    suspend fun addToCart(
        @Body body: JsonObject
    ): Response<JsonObject>

    @HTTP(method = "DELETE", path = ApiConstants.CART, hasBody = true)
    suspend fun removeFromCart(
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET(ApiConstants.ORDERS)
    suspend fun getOrders(): Response<JsonElement>

    @GET("api/v1/marketplace/orders/buyer")
    suspend fun getBuyerOrders(): Response<JsonElement>

    @GET(ApiConstants.BUSINESS_ORDERS)
    suspend fun getSellerOrders(): Response<JsonElement>

    @PUT("api/v1/business/orders/{id}/status")
    suspend fun updateOrderStatus(
        @Path("id") orderId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @POST(ApiConstants.ORDERS)
    suspend fun placeOrder(
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET(ApiConstants.BUSINESS_PROFILE)
    suspend fun getBusinessProfile(): Response<JsonObject>

    @POST(ApiConstants.BUSINESS_PROFILE)
    suspend fun createBusinessProfile(
        @Body body: JsonObject
    ): Response<JsonObject>

    @PUT(ApiConstants.BUSINESS_PROFILE)
    suspend fun updateBusinessProfile(
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET(ApiConstants.BUSINESS_PRODUCTS)
    suspend fun getMyProducts(): Response<JsonElement>

    @POST(ApiConstants.BUSINESS_PRODUCTS)
    suspend fun createProduct(
        @Body body: JsonObject
    ): Response<JsonObject>

    @PUT("api/v1/business/products/{id}")
    suspend fun updateProduct(
        @Path("id") productId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @DELETE("api/v1/business/products/{id}")
    suspend fun deleteProduct(
        @Path("id") productId: String
    ): Response<JsonObject>

    @POST("api/v1/marketplace/products/{id}/reviews")
    suspend fun createReview(
        @Path("id") productId: String,
        @Body body: JsonObject
    ): Response<JsonObject>

    @GET("api/v1/marketplace/products/{id}/reviews")
    suspend fun getReviews(
        @Path("id") productId: String
    ): Response<JsonElement>

    // ── Follow Store ─────────────────────────────────────────────

    @POST("api/v1/marketplace/stores/{id}/follow")
    suspend fun toggleFollowStore(
        @Path("id") storeId: String
    ): Response<JsonObject>

    @GET("api/v1/marketplace/stores/{id}/following")
    suspend fun isFollowingStore(
        @Path("id") storeId: String
    ): Response<JsonObject>

    @GET("api/v1/marketplace/followed-stores")
    suspend fun getFollowedStores(): Response<JsonElement>


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
