package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.*
import kotlinx.serialization.json.*

/**
 * Repository for Stories (Status updates) — fetch feed, post, view, get viewers.
 * Replaces the story portions of Flutter's `AppState`.
 */
class StoryRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val tokenManager: TokenManager get() = TokenManager.getInstance(context)

    // ═══════════════════════════════════════════════════════════════
    // ── Get Stories Feed ──────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    suspend fun getStories(): Result<List<UserStories>> {
        return try {
            val response = api.getStories()
            if (response.isSuccessful) {
                val body = response.body()
                val rawList = when (body) {
                    is JsonArray -> body
                    is JsonObject -> {
                        body["feed"]?.jsonArray
                            ?: body["stories"]?.jsonArray
                            ?: JsonArray(emptyList())
                    }
                    else -> JsonArray(emptyList())
                }

                val myUserId = tokenManager.userId ?: ""

                val stories = rawList.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val userId = (obj["user_id"] ?: obj["userId"])?.jsonPrimitive?.contentOrNull.orEmpty()
                        val userName = (obj["user_display_name"]
                            ?: obj["user_name"]
                            ?: obj["userName"]
                            ?: obj["author_name"])?.jsonPrimitive?.contentOrNull ?: "Contact"
                        val userAvatar = (obj["user_avatar_url"]
                            ?: obj["user_avatar"]
                            ?: obj["avatar_url"]
                            ?: obj["avatarUrl"])?.jsonPrimitive?.contentOrNull.orEmpty()

                        val storiesArr = (obj["stories"] ?: obj["items"])?.jsonArray
                            ?: JsonArray(emptyList())
                        val items = storiesArr.mapNotNull {
                            if (it is JsonObject) StoryItem.fromJson(it) else null
                        }

                        if (items.isEmpty()) null
                        else UserStories(
                            userId = userId,
                            userName = userName,
                            userAvatar = userAvatar,
                            stories = items,
                            isMe = myUserId.isNotBlank() && userId == myUserId
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                Result.success(stories)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Post Story ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    suspend fun postStory(
        mediaUrl: String,
        caption: String = "",
        mediaType: String = "image",
        backgroundColor: String? = null
    ): Result<Unit> {
        return try {
            val body = buildJsonObject {
                put("media_url", mediaUrl)
                put("content", caption)
                put("caption", caption)
                put("media_type", mediaType)
                backgroundColor?.let { put("background_color", it) }
            }
            val response = api.postStory(body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val err = response.errorBody()?.string().orEmpty()
                Result.failure(Exception(err.ifBlank { "Failed to post story" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadMedia(bytes: ByteArray, mimeType: String = "image/jpeg", fileName: String = "story_image.jpg"): String? {
        return try {
            val mediaType = okhttp3.MediaType.Companion.run { mimeType.toMediaTypeOrNull() }
            val reqBody = okhttp3.RequestBody.Companion.run { bytes.toRequestBody(mediaType) }
            val part = okhttp3.MultipartBody.Part.createFormData("file", fileName, reqBody)
            val response = api.uploadMedia(part)
            if (response.isSuccessful) {
                val json = response.body()
                val rawUrl = (json?.get("url") ?: json?.get("Url") ?: json?.get("URL") ?: json?.get("media_url"))?.jsonPrimitive?.contentOrNull
                if (!rawUrl.isNullOrBlank()) {
                    if (rawUrl.startsWith("/")) {
                        "${com.example.gochat.data.api.ApiConstants.BASE_URL.removeSuffix("/")}$rawUrl"
                    } else {
                        rawUrl
                    }
                } else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── View Story ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    suspend fun viewStory(storyId: String): Boolean {
        return try {
            val response = api.viewStory(storyId)
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Get Story Viewers ────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    suspend fun getStoryViewers(storyId: String): Result<List<StoryViewer>> {
        return try {
            val response = api.getStoryViewers(storyId)
            if (response.isSuccessful) {
                val body = response.body()
                val rawList = when (body) {
                    is JsonArray -> body
                    is JsonObject -> body["viewers"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                val viewers = rawList.mapNotNull {
                    if (it is JsonObject) StoryViewer.fromJson(it) else null
                }
                Result.success(viewers)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.success(emptyList())
        }
    }
}
