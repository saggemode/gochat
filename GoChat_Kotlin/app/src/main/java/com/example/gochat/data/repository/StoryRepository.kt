package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.model.*
import kotlinx.serialization.json.*

/**
 * Repository for Stories (Status updates) — fetch feed, post, view, get viewers.
 * Replaces the story portions of Flutter's `AppState`.
 */
class StoryRepository(private val context: Context) {

    private val api: GoChatApiService get() = NetworkModule.getApiService(context)
    private val json = NetworkModule.json

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

                val stories = rawList.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val userName = (obj["user_display_name"]
                            ?: obj["user_name"]
                            ?: obj["author_name"])?.jsonPrimitive?.contentOrNull ?: "Contact"
                        val userAvatar = (obj["user_avatar_url"]
                            ?: obj["user_avatar"]
                            ?: obj["avatar_url"])?.jsonPrimitive?.contentOrNull ?: ""

                        val storiesArr = (obj["stories"] ?: obj["items"])?.jsonArray
                            ?: JsonArray(emptyList())
                        val items = storiesArr.map { json.decodeFromJsonElement<StoryItem>(it) }

                        if (items.isEmpty()) null
                        else UserStories(
                            userId = userId,
                            userName = userName,
                            userAvatar = userAvatar,
                            stories = items
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
                val viewers = rawList.map { json.decodeFromJsonElement<StoryViewer>(it) }
                Result.success(viewers)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.success(emptyList())
        }
    }
}
