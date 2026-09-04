package com.example.gochat.data.db

import androidx.room.*
import com.example.gochat.data.model.CallRecord
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // ── Conversations ──────────────────────────────────────────
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(convs: List<Conversation>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conv: Conversation)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :convId")
    suspend fun markConversationAsRead(convId: String)

    @Query("DELETE FROM conversations WHERE id = :convId")
    suspend fun deleteConversation(convId: String)

    // ── Messages ───────────────────────────────────────────────
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun getMessagesForConversation(convId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getLatestMessages(convId: String, limit: Int = 50): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun clearMessagesForConversation(convId: String)

    // ── Calls ──────────────────────────────────────────────────
    @Query("SELECT * FROM calls ORDER BY timestamp DESC")
    fun getAllCalls(): Flow<List<CallRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(call: CallRecord)

    @Query("DELETE FROM calls")
    suspend fun clearAllCalls()
}
