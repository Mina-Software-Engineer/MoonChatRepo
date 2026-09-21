package com.mina.moonchat.data.local

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mina.moonchat.data.dto.MessageStatus
import com.mina.moonchat.data.dto.MessagesDTO


/*@Dao
interface MessageDao {

    // Get all messages for a channel, with pagination
    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId ORDER BY message_time ASC LIMIT :limit OFFSET :offset")
    suspend fun getAllMessagesInfo(channelId: String, limit: Int, offset: Int): List<MessagesDTO>

    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId AND recipient_id = :recipient_Id ORDER BY message_time DESC LIMIT 1")
    fun getLastMessageInfo(channelId: String, recipient_Id: String): LiveData<MessagesDTO>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addCurrentMessageInfo(messagesInfo: MessagesDTO)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAllMessagesToDB(chatMessages: List<MessagesDTO>)
}*/




@Dao
interface MessageDao {

    /**
     * Primary pagination query - ordered by firebase_timestamp DESC for consistent ordering
     * Using firebase_timestamp ensures proper ordering even with offline messages
     */
    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId ORDER BY firebase_timestamp DESC, message_time DESC")
    fun getMessagesPaged(channelId: String): PagingSource<Int, MessagesDTO>

    /**
     * Insert or update message (prevents duplicates)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessagesDTO)

    /**
     * Insert multiple messages, ignoring duplicates
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreExisting(messages: List<MessagesDTO>)

    /**
     * Insert or update multiple messages
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessagesDTO>)

    /**
     * Check if message exists by ID
     */
    @Query("SELECT EXISTS(SELECT 1 FROM MessageEntity WHERE message_id = :messageId)")
    suspend fun messageExists(messageId: String): Boolean

    /**
     * Get message by ID
     */
    @Query("SELECT * FROM MessageEntity WHERE message_id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessagesDTO?

    /**
     * Get the oldest message for a channel (for pagination)
     */
    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId ORDER BY firebase_timestamp ASC, message_time ASC LIMIT 1")
    suspend fun getOldestMessage(channelId: String): MessagesDTO?

    /**
     * Get the newest message for a channel
     */
    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId ORDER BY firebase_timestamp DESC, message_time DESC LIMIT 1")
    suspend fun getNewestMessage(channelId: String): MessagesDTO?

    /**
     * Get last message for a specific recipient (for chat list)
     */
    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId ORDER BY firebase_timestamp DESC, message_time DESC LIMIT 1")
    suspend fun getLastMessage(channelId: String): MessagesDTO?

    /**
     * Get pending messages (for retry)
     */
    @Query("SELECT * FROM MessageEntity WHERE status = 'PENDING' OR status = 'FAILED'")
    suspend fun getPendingMessages(): List<MessagesDTO>

    /**
     * Update message status
     */
    @Query("UPDATE MessageEntity SET status = :status WHERE message_id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    /**
     * Update message ID (used when Firebase generates the real ID)
     */
    @Query("UPDATE MessageEntity SET message_id = :newId, status = :status WHERE message_id = :oldId")
    suspend fun updateMessageId(oldId: String, newId: String, status: MessageStatus = MessageStatus.SYNCED)

    @Query("DELETE FROM MessageEntity WHERE message_id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    /**
     * Mark messages as read
     */
    @Query("UPDATE MessageEntity SET is_read = 1 WHERE channelID = :channelId AND sender_id != :currentUserId")
    suspend fun markMessagesAsRead(channelId: String, currentUserId: String)

    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId AND sender_id != :currentUserId AND is_read = 0")
    suspend fun getUnreadMessages(channelId: String, currentUserId: String): List<MessagesDTO>

    /**
     * Get unread count for a channel
     */
    @Query("SELECT COUNT(*) FROM MessageEntity WHERE channelID = :channelId AND sender_id != :currentUserId AND is_read = 0")
    suspend fun getUnreadCount(channelId: String, currentUserId: String): Int

    /**
     * Delete all messages for a channel
     */
    @Query("DELETE FROM MessageEntity WHERE channelID = :channelId")
    suspend fun clearMessages(channelId: String)

    /**
     * Delete old messages (for cleanup)
     */
    @Query("DELETE FROM MessageEntity WHERE message_time < :timestamp")
    suspend fun deleteOldMessages(timestamp: Long)

    // Legacy methods for backward compatibility
    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId ORDER BY message_time ASC LIMIT :limit OFFSET :offset")
    suspend fun getAllMessagesInfo(channelId: String, limit: Int, offset: Int): List<MessagesDTO>

    @Query("SELECT * FROM MessageEntity WHERE channelID = :channelId AND recipient_id = :recipient_Id ORDER BY message_time DESC LIMIT 1")
    fun getLastMessageInfo(channelId: String, recipient_Id: String): List<MessagesDTO>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addCurrentMessageInfo(messagesInfo: MessagesDTO)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAllMessagesToDB(chatMessages: List<MessagesDTO>)
}
