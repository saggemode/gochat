package com.example.gochat.data.repository

import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.model.Moment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Moments: a WeChat-style social feed. Backed by the social service. */
@Singleton
class MomentsRepository @Inject constructor(
    private val api: GoChatApiService
) {

    suspend fun getFeed(): Result<List<Moment>> = try {
        val resp = api.getMomentsFeed()
        if (resp.isSuccessful) Result.success(resp.body()?.moments ?: emptyList())
        else Result.failure(Exception("Failed to load feed"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun create(content: String): Result<Unit> = try {
        val body = buildJsonObject {
            put("content", content)
            put("visibility", "public")
        }
        val resp = api.createMoment(body)
        if (resp.isSuccessful) Result.success(Unit) else Result.failure(Exception("Failed to post"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun like(id: String): Boolean = try { api.likeMoment(id).isSuccessful } catch (_: Exception) { false }

    suspend fun comment(id: String, text: String): Boolean = try {
        val body = buildJsonObject { put("content", text) }
        api.commentMoment(id, body).isSuccessful
    } catch (_: Exception) {
        false
    }
}
