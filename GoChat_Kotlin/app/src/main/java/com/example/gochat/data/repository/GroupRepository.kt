package com.example.gochat.data.repository



import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.model.GroupEvent
import com.example.gochat.data.model.GroupMember
import com.example.gochat.data.model.GroupMetadata
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val api: GoChatApiService
) {

    suspend fun getGroupMembers(convId: String): Result<List<GroupMember>> {
        return try {
            val response = api.getGroupMembers(convId)
            if (response.isSuccessful) {
                val list = response.body()?.members ?: emptyList()
                Result.success(list)
            } else {
                Result.failure(Exception("Failed to fetch group members"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Group events ──────────────────────────────────────────────────────────
    suspend fun listGroupEvents(convId: String): Result<List<GroupEvent>> {
        return try {
            val response = api.listGroupEvents(convId)
            if (response.isSuccessful) Result.success(response.body() ?: emptyList())
            else Result.failure(Exception("Failed to load events"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createGroupEvent(
        convId: String,
        title: String,
        eventTimeMillis: Long,
        location: String = "",
        description: String = ""
    ): Result<GroupEvent> {
        return try {
            val body = buildJsonObject {
                put("title", title)
                put("event_time", eventTimeMillis / 1000) // server stores Unix seconds
                if (location.isNotBlank()) put("location", location)
                if (description.isNotBlank()) put("description", description)
            }
            val response = api.createGroupEvent(convId, body)
            if (response.isSuccessful && response.body() != null) Result.success(response.body()!!)
            else Result.failure(Exception("Failed to create event"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rsvpGroupEvent(convId: String, eventId: String, response: String): Result<GroupEvent> {
        return try {
            val body = buildJsonObject { put("response", response) }
            val resp = api.rsvpGroupEvent(convId, eventId, body)
            if (resp.isSuccessful && resp.body() != null) Result.success(resp.body()!!)
            else Result.failure(Exception("Failed to RSVP"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroupMetadata(convId: String): Result<GroupMetadata> {
        return try {
            val response = api.getGroupMetadata(convId)
            if (response.isSuccessful) {
                val meta = response.body() ?: throw Exception("Empty metadata")
                Result.success(meta)
            } else {
                Result.failure(Exception("Failed to fetch group metadata"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateGroupMetadata(
        convId: String,
        name: String? = null,
        description: String? = null,
        avatarUrl: String? = null,
        announcementsOnly: Boolean? = null,
        adminsOnlyEditInfo: Boolean? = null,
        joinApprovalRequired: Boolean? = null
    ): Result<GroupMetadata> {
        return try {
            val body = buildJsonObject {
                name?.let { put("name", it) }
                description?.let { put("description", it) }
                avatarUrl?.let { put("avatar_url", it) }
                announcementsOnly?.let { put("announcements_only", it) }
                adminsOnlyEditInfo?.let { put("admins_only_edit_info", it) }
                joinApprovalRequired?.let { put("join_approval_required", it) }
            }
            val response = api.updateGroupMetadata(convId, body)
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                val errorBodyStr = response.errorBody()?.string().orEmpty()
                val errMsg = try {
                    if (errorBodyStr.isNotBlank()) {
                        val obj = Json.parseToJsonElement(errorBodyStr).jsonObject
                        obj["error"]?.jsonPrimitive?.contentOrNull ?: errorBodyStr
                    } else null
                } catch (e: Exception) { null } ?: "Failed to update group metadata"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateInviteLink(convId: String): Result<String> {
        return try {
            val response = api.generateInviteLink(convId)
            if (response.isSuccessful) {
                val code = response.body()?.get("invite_code")?.jsonPrimitive?.content ?: ""
                Result.success(code)
            } else {
                Result.failure(Exception("Failed to generate invite code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinByInviteCode(code: String): Result<String> {
        return try {
            val body = buildJsonObject { put("invite_code", code) }
            val response = api.joinByInviteCode(body)
            if (response.isSuccessful) {
                val convId = response.body()?.get("conversation_id")?.jsonPrimitive?.content ?: ""
                Result.success(convId)
            } else {
                Result.failure(Exception("Failed to join group"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addMember(convId: String, userId: String): Result<Unit> {
        return try {
            val body = buildJsonObject { put("new_member_id", userId) }
            val response = api.addMember(convId, body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to add member"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeMember(convId: String, userId: String): Result<Unit> {
        return try {
            val body = buildJsonObject { put("member_id", userId) }
            val response = api.removeMember(convId, body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to remove member"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun promoteMember(convId: String, userId: String, role: String = "admin"): Result<Unit> {
        return try {
            val body = buildJsonObject { put("role", role) }
            val response = api.promoteMember(convId, userId, body)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to promote member"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun demoteMember(convId: String, userId: String): Result<Unit> {
        return try {
            val response = api.demoteMember(convId, userId)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to demote member"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
