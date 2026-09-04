package com.example.gochat.data.api

/**
 * Centralised API endpoint constants — mirrors Flutter's `api_constants.dart`.
 */
object ApiConstants {
    // ── Base URLs ────────────────────────────────────────────────
    const val PROD_BASE_URL = "https://gochat-kvpj.onrender.com"
    const val LOCAL_BASE_URL = "http://localhost:8080"
    const val EMULATOR_BASE_URL = "http://10.0.2.2:8080"

    // Active base URL (swap for local dev when needed)
    const val BASE_URL = PROD_BASE_URL
    const val API_V1 = "$BASE_URL/api/v1"

    // ── Auth ─────────────────────────────────────────────────────
    const val REGISTER = "api/v1/auth/register"
    const val LOGIN = "api/v1/auth/login"
    const val REFRESH = "api/v1/auth/refresh"
    const val REQUEST_OTP = "api/v1/auth/otp/request"
    const val VERIFY_OTP = "api/v1/auth/otp/verify"
    const val SYNC_CONTACTS = "api/v1/users/sync"
    const val USER_PROFILE = "api/v1/users/me"

    // ── Conversations & Messages ─────────────────────────────────
    const val CONVERSATIONS = "api/v1/chat/conversations"
    fun conversationMessages(convId: String) = "api/v1/chat/conversations/$convId/messages"
    fun pollVote(pollId: String) = "api/v1/chat/polls/$pollId/vote"

    // ── Stories / Status ─────────────────────────────────────────
    const val STORIES = "api/v1/stories"
    const val MY_STORIES = "api/v1/stories/my"
    fun storyView(storyId: String) = "api/v1/stories/$storyId/view"
    fun storyViewers(storyId: String) = "api/v1/stories/$storyId/viewers"

    // ── Calls ────────────────────────────────────────────────────
    const val CALLS = "api/v1/calls"
    const val CALL_HISTORY = "api/v1/calls/history"
    fun callAction(callId: String, action: String) = "api/v1/calls/$callId/$action"
    fun callSignaling(callId: String) = "api/v1/calls/$callId/signaling"

    // ── Channels ─────────────────────────────────────────────────
    const val CHANNELS = "api/v1/channels"
    fun channelFeed(channelId: String) = "api/v1/channels/$channelId/feed"

    // ── Marketplace & Business ───────────────────────────────────
    const val BUSINESS_PROFILE = "api/v1/business/profile"
    const val BUSINESS_PRODUCTS = "api/v1/business/products"
    const val BUSINESS_ORDERS = "api/v1/business/orders"
    const val BUSINESS_COUPONS = "api/v1/business/coupons"
    const val PRODUCTS = "api/v1/marketplace/products"
    const val CATEGORIES = "api/v1/marketplace/categories"
    const val CART = "api/v1/marketplace/cart"
    const val ORDERS = "api/v1/marketplace/orders"
    const val WISHLIST = "api/v1/marketplace/wishlist"
    const val VALIDATE_COUPON = "api/v1/marketplace/coupons/validate"

    // ── Media Upload / Download ──────────────────────────────────
    const val MEDIA_UPLOAD = "api/v1/media/upload"
    fun mediaDownload(fileId: String) = "api/v1/media/download/$fileId"

    // ── WebSocket URL ────────────────────────────────────────────
    val WS_URL: String
        get() {
            val protocol = if (BASE_URL.startsWith("https")) "wss" else "ws"
            val host = BASE_URL
                .removePrefix("https://")
                .removePrefix("http://")
            return "$protocol://$host/ws"
        }
}
