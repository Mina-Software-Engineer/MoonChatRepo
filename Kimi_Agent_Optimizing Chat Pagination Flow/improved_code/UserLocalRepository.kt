package com.mina.moonchat.data.server

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.mina.moonchat.data.dto.MessageStatus
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.toMessagesDTO
import com.mina.moonchat.data.dto.toTextMessage
import com.mina.moonchat.data.local.UserDatabase
import com.mina.moonchat.models.TextMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*

/**
 * Improved UserLocalRepository with:
 * - Proper message status handling
 * - Duplicate prevention
 * - Consistent channel ID generation
 * - Better real-time sync management
 */
class UserLocalRepository(
    private val db: UserDatabase,
    private val server: ServerSide
) {
    private val msgDao = db.messageDao()
    private val mAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    companion object {
        private const val TAG = "UserLocalRepository"
        private const val PAGE_SIZE = 20
    }

    // Track active listeners to prevent duplicates
    private var activeListener: Pair<String, ChildEventListener>? = null

    /**
     * Generate consistent channel ID
     */
    fun getChannelId(userId1: String, userId2: String): String {
        return server.getChatChannelId(userId1, userId2)
    }

    /**
     * Main entry point: Get paginated message stream
     * Combines Room database with Firebase RemoteMediator
     */
    @OptIn(ExperimentalPagingApi::class)
    fun getChatStream(currentUserId: String, recipientId: String): Flow<PagingData<TextMessage>> {
        val channelId = getChannelId(currentUserId, recipientId)

        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = 5
            ),
            remoteMediator = ChatRemoteMediator(
                channelId = channelId,
                currentUserId = currentUserId,
                recipientId = recipientId,
                database = db,
                server = server
            ),
            pagingSourceFactory = { msgDao.getMessagesPaged(channelId) }
        ).flow.map { pagingData ->
            pagingData.map { it.toTextMessage() }
        }
    }

    /**
     * Activate real-time sync for new messages
     * Should be called when entering a chat
     */
    fun activateRealtimeSync(currentUserId: String, recipientId: String) {
        // Remove existing listener if any
        removeActiveListener()

        val channelId = getChannelId(currentUserId, recipientId)

        CoroutineScope(Dispatchers.IO).launch {
            // Get the timestamp of the newest message we have
            val newestMessage = msgDao.getNewestMessage(channelId)
            val fromTimestamp = newestMessage?.firebaseTimestamp ?: System.currentTimeMillis()

            Log.d(TAG, "Starting realtime sync from timestamp: $fromTimestamp")

            val listener = server.listenForNewMessages(
                currentUserId,
                recipientId,
                fromTimestamp
            ) { newMessage ->
                handleIncomingMessage(newMessage, currentUserId)
            }

            activeListener = channelId to listener
        }
    }

    /**
     * Stop real-time sync
     * Should be called when leaving a chat
     */
    fun stopRealtimeSync(currentUserId: String, recipientId: String) {
        removeActiveListener()
        Log.d(TAG, "Realtime sync stopped")
    }

    private fun removeActiveListener() {
        activeListener?.let { (channelId, listener) ->
            val userIds = channelId.split("_")
            if (userIds.size == 2) {
                server.removeMessageListener(userIds[0], userIds[1], listener)
            }
            activeListener = null
        }
    }

    /**
     * Handle incoming message from Firebase
     * Prevents duplicates and updates existing messages
     */
    private fun handleIncomingMessage(message: TextMessage, currentUserId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channelId = getChannelId(message.senderId, message.recipientId)

                // Check if message already exists
                val existingMessage = msgDao.getMessageById(message.id)

                if (existingMessage != null) {
                    // Message exists - update if needed
                    if (existingMessage.status == MessageStatus.PENDING) {
                        // This was our pending message that got confirmed
                        msgDao.updateMessageStatus(message.id, MessageStatus.SYNCED)
                        Log.d(TAG, "Pending message confirmed: ${message.id}")
                    }
                    // Otherwise, it's a duplicate - ignore
                } else {
                    // New message - determine status
                    val status = when {
                        message.senderId == currentUserId -> MessageStatus.SYNCED
                        else -> MessageStatus.RECEIVED
                    }

                    val messageDTO = message.toMessagesDTO(status).copy(
                        channelID = channelId,
                        firebaseTimestamp = message.date.time
                    )

                    msgDao.insertMessage(messageDTO)
                    Log.d(TAG, "New message saved: ${message.id}, status: $status")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming message: ${e.message}")
            }
        }
    }

    /**
     * Send message with optimistic UI update
     */
    suspend fun sendMessage(message: TextMessage) {
        val channelId = getChannelId(message.senderId, message.recipientId)

        // 1. Generate temporary ID for optimistic update
        val tempId = "temp_${System.currentTimeMillis()}"
        val pendingMessage = message.copy(
            id = tempId,
            status = "PENDING"
        )

        // 2. Save to Room immediately (optimistic UI)
        val pendingDTO = pendingMessage.toMessagesDTO(MessageStatus.PENDING).copy(
            channelID = channelId,
            firebaseTimestamp = System.currentTimeMillis()
        )
        msgDao.insertMessage(pendingDTO)
        Log.d(TAG, "Pending message saved: $tempId")

        // 3. Send to Firebase
        server.sendMessage(
            message = message,
            onSuccess = { firebaseId ->
                // 4. Update local message with Firebase ID
                CoroutineScope(Dispatchers.IO).launch {
                    msgDao.updateMessageId(tempId, firebaseId, MessageStatus.SENT)
                    Log.d(TAG, "Message updated with Firebase ID: $firebaseId")

                    // Update chat channel metadata
                    server.updateChatChannel(
                        userId = message.senderId,
                        recipientId = message.recipientId,
                        recipientName = message.recipientName,
                        lastMessage = message.text
                    )
                }
            },
            onError = { error ->
                // 5. Mark as failed
                CoroutineScope(Dispatchers.IO).launch {
                    msgDao.updateMessageStatus(tempId, MessageStatus.FAILED)
                    Log.e(TAG, "Failed to send message: ${error.message}")
                }
            }
        )
    }

    /**
     * Retry sending a failed message
     */
    suspend fun retryMessage(message: TextMessage) {
        // Remove the failed message
        msgDao.messageExists(message.id)

        // Send again
        sendMessage(message.copy(status = "PENDING"))
    }

    /**
     * Mark messages as read
     */
    suspend fun markMessagesAsRead(channelId: String, currentUserId: String) {
        msgDao.markMessagesAsRead(channelId, currentUserId)
    }

    /**
     * Get unread count for a channel
     */
    suspend fun getUnreadCount(channelId: String, currentUserId: String): Int {
        return msgDao.getUnreadCount(channelId, currentUserId)
    }

    /**
     * Clear chat history for a channel
     */
    suspend fun clearChatHistory(channelId: String) {
        msgDao.clearMessages(channelId)
    }

    /**
     * Insert a new message directly (for testing or migration)
     */
    suspend fun insertMessage(message: TextMessage, status: MessageStatus = MessageStatus.SENT) {
        val channelId = getChannelId(message.senderId, message.recipientId)
        val dto = message.toMessagesDTO(status).copy(channelID = channelId)
        msgDao.insertMessage(dto)
    }

    /**
     * Sync pending messages on app startup
     */
    suspend fun syncPendingMessages() {
        val pendingMessages = msgDao.getPendingMessages()
        Log.d(TAG, "Found ${pendingMessages.size} pending messages to sync")

        pendingMessages.forEach { dto ->
            val message = dto.toTextMessage()
            retryMessage(message)
        }
    }
}
