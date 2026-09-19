package com.example.gochat.data.db

import androidx.paging.PagingSource
import androidx.room.*
import com.example.gochat.data.model.CallRecord
import com.example.gochat.data.model.CallStatus
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageStatus
import com.example.gochat.data.model.Reaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // ── Conversations ──────────────────────────────────────────
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllConversationsPaged(): PagingSource<Int, Conversation>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(convs: List<Conversation>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conv: Conversation)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :convId")
    suspend fun markConversationAsRead(convId: String): Int

    @Query("UPDATE messages SET status = 'READ' WHERE conversationId = :convId AND isMe = 1 AND status != 'READ'")
    suspend fun markOutgoingMessagesAsRead(convId: String): Int

    @Query("UPDATE messages SET status = 'READ' WHERE conversationId = :convId AND isMe = 0 AND status != 'READ'")
    suspend fun markIncomingMessagesAsRead(convId: String): Int

    @Query("UPDATE conversations SET lastMessageText = :lastText, lastMessageTime = :lastTime, updatedAt = :updatedAt, unreadCount = unreadCount + 1 WHERE id = :convId")
    suspend fun updateLastMessageAndIncrementUnread(convId: String, lastText: String, lastTime: Long, updatedAt: Long): Int

    @Query("UPDATE conversations SET lastMessageText = :lastText, lastMessageTime = :lastTime, updatedAt = :updatedAt WHERE id = :convId")
    suspend fun updateLastMessage(convId: String, lastText: String, lastTime: Long, updatedAt: Long): Int

    @Query("UPDATE conversations SET screenshotNotificationsEnabled = :enabled WHERE id = :convId")
    suspend fun updateScreenshotNotifications(convId: String, enabled: Boolean): Int

    @Query("UPDATE conversations SET disappearingMessagesDuration = :durationSeconds WHERE id = :convId")
    suspend fun updateDisappearingMessagesDuration(convId: String, durationSeconds: Int): Int

    @Query("UPDATE conversations SET isOnline = :isOnline, lastSeen = :lastSeen WHERE type = 'DIRECT' AND memberIds LIKE '%' || :userId || '%'")
    suspend fun updatePresenceGlobal(userId: String, isOnline: Boolean, lastSeen: Long): Int

    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsList(): List<Conversation>

    @Query("DELETE FROM conversations WHERE id = :convId")
    suspend fun deleteConversation(convId: String): Int

    @Query("DELETE FROM conversations")
    suspend fun clearAllConversations(): Int

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages(): Int

    // ── Messages ───────────────────────────────────────────────
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt DESC")
    fun getMessagesForConversation(convId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt DESC")
    fun getMessagesForConversationPaged(convId: String): PagingSource<Int, Message>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getLatestMessages(convId: String, limit: Int = 50): List<Message>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): Message?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)

    @Query("SELECT * FROM messages WHERE status = 'SENDING' ORDER BY createdAt ASC")
    suspend fun getPendingMessages(): List<Message>

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus): Int

    @Query("UPDATE messages SET content = :newContent, isEdited = 1 WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, newContent: String): Int

    @Query("UPDATE messages SET isDeleted = 1, content = 'This message was deleted' WHERE id = :messageId")
    suspend fun markMessageAsDeleted(messageId: String): Int

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :messageId")
    suspend fun updateMessageStarred(messageId: String, isStarred: Boolean): Int

    @Query("UPDATE messages SET isPinned = :isPinned WHERE id = :messageId")
    suspend fun updateMessagePinned(messageId: String, isPinned: Boolean): Int

    @Query("UPDATE messages SET isViewed = 1 WHERE id = :messageId")
    suspend fun markMessageAsViewed(messageId: String): Int

    @Query("SELECT * FROM messages WHERE conversationId = :convId AND isPinned = 1 ORDER BY createdAt DESC")
    fun getPinnedMessagesForConversation(convId: String): Flow<List<Message>>

    @Query("UPDATE messages SET reactions = :reactions WHERE id = :messageId")
    suspend fun updateMessageReactions(messageId: String, reactions: List<Reaction>): Int

    @Query("SELECT * FROM messages WHERE isStarred = 1 ORDER BY createdAt DESC")
    fun getStarredMessages(): Flow<List<Message>>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String): Int

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun clearMessagesForConversation(convId: String): Int

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt < :currentTime")
    suspend fun deleteExpiredMessages(currentTime: Long): Int

    @Query("SELECT * FROM messages WHERE conversationId = :convId AND isDeleted = 0 AND content LIKE '%' || :query || '%' ORDER BY createdAt ASC")
    suspend fun searchMessagesInConversation(convId: String, query: String): List<Message>

    @Query("SELECT * FROM messages WHERE isDeleted = 0 AND content LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT 100")
    suspend fun searchAllMessages(query: String): List<Message>

    @Query("SELECT * FROM messages WHERE conversationId = :convId AND isDeleted = 0 AND isViewOnce = 0 AND (type = 'IMAGE' OR type = 'VIDEO') AND mediaUrl IS NOT NULL AND mediaUrl != '' ORDER BY createdAt DESC")
    fun getMediaMessagesForConversation(convId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId AND isDeleted = 0 AND type = 'FILE' AND mediaUrl IS NOT NULL AND mediaUrl != '' ORDER BY createdAt DESC")
    fun getDocumentMessagesForConversation(convId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId AND isDeleted = 0 AND (content LIKE '%http://%' OR content LIKE '%https://%' OR content LIKE '%www.%') ORDER BY createdAt DESC")
    fun getLinkMessagesForConversation(convId: String): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :convId AND isDeleted = 0 AND ((isViewOnce = 0 AND (type = 'IMAGE' OR type = 'VIDEO' OR type = 'FILE') AND mediaUrl IS NOT NULL AND mediaUrl != '') OR content LIKE '%http://%' OR content LIKE '%https://%' OR content LIKE '%www.%')")
    fun getSharedMediaCount(convId: String): Flow<Int>

    @Query("SELECT * FROM messages WHERE conversationId = :convId AND isDeleted = 0 AND isViewOnce = 0 AND (type = 'IMAGE' OR type = 'VIDEO') AND mediaUrl IS NOT NULL AND mediaUrl != '' ORDER BY createdAt DESC LIMIT 10")
    fun getRecentMediaPreviews(convId: String): Flow<List<Message>>

    // ── Calls ──────────────────────────────────────────────────
    @Query("SELECT * FROM calls ORDER BY timestamp DESC")
    fun getAllCalls(): Flow<List<CallRecord>>

    @Query("SELECT * FROM calls WHERE status = 'MISSED' ORDER BY timestamp DESC")
    fun getMissedCalls(): Flow<List<CallRecord>>

    @Query("SELECT COUNT(*) FROM calls WHERE status = 'MISSED'")
    fun getMissedCallsCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(call: CallRecord)

    @Query("UPDATE calls SET status = :status, durationSeconds = :durationSeconds WHERE id = :id")
    suspend fun updateCallStatus(id: String, status: CallStatus, durationSeconds: Int): Int

    @Query("DELETE FROM calls WHERE id = :id")
    suspend fun deleteCall(id: String): Int

    @Query("DELETE FROM calls")
    suspend fun clearAllCalls(): Int
}
