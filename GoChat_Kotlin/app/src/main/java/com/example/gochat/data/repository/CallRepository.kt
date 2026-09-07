package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.db.ChatDao
import com.example.gochat.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for voice/video calls — start, accept, reject, end, signaling, history.
 * Persists call history in Room for the Calls tab.
 */
@Singleton
class CallRepository @Inject constructor(
    private val api: GoChatApiService,
    private val dao: ChatDao,
    private val tokenManager: TokenManager
) {

    /** Room Flow for the calls history list. */
    fun observeCalls(): Flow<List<CallRecord>> = dao.getAllCalls()

    // ═══════════════════════════════════════════════════════════════
    // ── Call Actions ─────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    suspend fun startCall(receiverId: String, type: String = "voice"): Result<CallRecord> {
        return try {
            val body = buildJsonObject {
                put("receiver_id", receiverId)
                put("type", type)
            }
            val response = api.startCall(body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val call = CallRecord.fromJson(data, tokenManager.userId ?: "")
                dao.insertCall(call)
                Result.success(call)
            } else {
                val err = response.errorBody()?.string().orEmpty()
                Result.failure(Exception(err.ifBlank { "Failed to start call" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptCall(callId: String): Result<CallRecord> {
        return try {
            val response = api.acceptCall(callId)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val call = CallRecord.fromJson(data, tokenManager.userId ?: "")
                dao.insertCall(call)
                Result.success(call)
            } else {
                Result.failure(Exception("Failed to accept call"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rejectCall(callId: String, isBusy: Boolean = false): Result<CallRecord> {
        return try {
            val body = buildJsonObject { put("is_busy", isBusy) }
            val response = api.rejectCall(callId, body)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val call = CallRecord.fromJson(data, tokenManager.userId ?: "")
                dao.insertCall(call)
                Result.success(call)
            } else {
                Result.failure(Exception("Failed to reject call"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun endCall(callId: String): Result<CallRecord> {
        return try {
            val response = api.endCall(callId)
            if (response.isSuccessful) {
                val data = response.body() ?: buildJsonObject {}
                val call = CallRecord.fromJson(data, tokenManager.userId ?: "")
                dao.insertCall(call)
                Result.success(call)
            } else {
                Result.failure(Exception("Failed to end call"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Signaling ────────────────────────────────────────────────

    suspend fun sendSignaling(
        callId: String,
        receiverId: String,
        type: String,
        sdp: String? = null,
        candidate: String? = null
    ): Boolean {
        return try {
            val body = buildJsonObject {
                put("receiver_id", receiverId)
                put("type", type)
                sdp?.let { put("sdp", it) }
                candidate?.let { put("candidate", it) }
            }
            val response = api.sendSignaling(callId, body)
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    // ── History ──────────────────────────────────────────────────

    suspend fun refreshCallHistory(): Result<List<CallRecord>> {
        return try {
            val response = api.getCallHistory()
            if (response.isSuccessful) {
                val body = response.body()
                val rawList = when (body) {
                    is JsonArray -> body
                    is JsonObject -> body["calls"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                val currentUserId = tokenManager.userId ?: ""
                val calls = rawList.mapNotNull {
                    if (it is JsonObject) CallRecord.fromJson(it, currentUserId) else null
                }
                dao.clearAllCalls()
                calls.forEach { dao.insertCall(it) }
                Result.success(calls)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearCallHistory() {
        dao.clearAllCalls()
    }
}
