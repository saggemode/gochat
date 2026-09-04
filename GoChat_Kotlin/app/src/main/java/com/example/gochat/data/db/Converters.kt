package com.example.gochat.data.db

import androidx.room.TypeConverter
import com.example.gochat.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return value?.let { json.encodeToString(it) } ?: "[]"
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromMessageType(value: MessageType?): String = (value ?: MessageType.TEXT).name

    @TypeConverter
    fun toMessageType(value: String?): MessageType {
        return try {
            MessageType.valueOf(value ?: "TEXT")
        } catch (e: Exception) {
            MessageType.TEXT
        }
    }

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus?): String = (value ?: MessageStatus.SENT).name

    @TypeConverter
    fun toMessageStatus(value: String?): MessageStatus {
        return try {
            MessageStatus.valueOf(value ?: "SENT")
        } catch (e: Exception) {
            MessageStatus.SENT
        }
    }

    @TypeConverter
    fun fromConversationType(value: ConversationType?): String = (value ?: ConversationType.DIRECT).name

    @TypeConverter
    fun toConversationType(value: String?): ConversationType {
        return try {
            ConversationType.valueOf(value ?: "DIRECT")
        } catch (e: Exception) {
            ConversationType.DIRECT
        }
    }

    @TypeConverter
    fun fromInvitationStatus(value: InvitationStatus?): String = (value ?: InvitationStatus.NONE).name

    @TypeConverter
    fun toInvitationStatus(value: String?): InvitationStatus {
        return try {
            InvitationStatus.valueOf(value ?: "NONE")
        } catch (e: Exception) {
            InvitationStatus.NONE
        }
    }

    @TypeConverter
    fun fromCallType(value: CallType?): String = (value ?: CallType.AUDIO).name

    @TypeConverter
    fun toCallType(value: String?): CallType {
        return try {
            CallType.valueOf(value ?: "AUDIO")
        } catch (e: Exception) {
            CallType.AUDIO
        }
    }

    @TypeConverter
    fun fromCallStatus(value: CallStatus?): String = (value ?: CallStatus.OUTGOING).name

    @TypeConverter
    fun toCallStatus(value: String?): CallStatus {
        return try {
            CallStatus.valueOf(value ?: "OUTGOING")
        } catch (e: Exception) {
            CallStatus.OUTGOING
        }
    }
}
