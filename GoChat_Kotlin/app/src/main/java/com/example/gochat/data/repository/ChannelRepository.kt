package com.example.gochat.data.repository

import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.model.Channel
import com.example.gochat.data.model.ChannelMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Channels: WhatsApp-style one-to-many broadcast. Backed by the channel service. */
@Singleton
class ChannelRepository @Inject constructor(
    private val api: GoChatApiService
) {

    suspend fun listChannels(subscribedOnly: Boolean = false): Result<List<Channel>> = try {
        val resp = api.listChannels(if (subscribedOnly) "true" else null)
        if (resp.isSuccessful) Result.success(resp.body() ?: emptyList())
        else Result.failure(Exception("Failed to load channels"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun createChannel(name: String, description: String): Result<Channel> = try {
        val body = buildJsonObject {
            put("name", name)
            if (description.isNotBlank()) put("description", description)
        }
        val resp = api.createChannel(body)
        if (resp.isSuccessful && resp.body() != null) Result.success(resp.body()!!)
        else Result.failure(Exception("Failed to create channel"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getChannel(id: String): Result<Channel> = try {
        val resp = api.getChannel(id)
        if (resp.isSuccessful && resp.body() != null) Result.success(resp.body()!!)
        else Result.failure(Exception("Channel not found"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun subscribe(id: String): Boolean = try { api.subscribeChannel(id).isSuccessful } catch (_: Exception) { false }
    suspend fun unsubscribe(id: String): Boolean = try { api.unsubscribeChannel(id).isSuccessful } catch (_: Exception) { false }
    suspend fun deleteChannel(id: String): Boolean = try { api.deleteChannel(id).isSuccessful } catch (_: Exception) { false }

    suspend fun getMessages(id: String): Result<List<ChannelMessage>> = try {
        val resp = api.getChannelMessages(id)
        if (resp.isSuccessful) Result.success(resp.body() ?: emptyList())
        else Result.failure(Exception("Failed to load posts"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun publish(id: String, content: String): Result<ChannelMessage> = try {
        val body = buildJsonObject {
            put("content", content)
            put("type", "text")
        }
        val resp = api.publishChannelMessage(id, body)
        if (resp.isSuccessful && resp.body() != null) Result.success(resp.body()!!)
        else Result.failure(Exception("Failed to publish"))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
