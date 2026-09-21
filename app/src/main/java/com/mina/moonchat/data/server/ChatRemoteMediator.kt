package com.mina.moonchat.data.server

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.mina.moonchat.data.dto.MessageStatus
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.local.UserDatabase

/**
 * This is the bridge. When the user scrolls to the top of the list in Room,
 * Paging 3 calls this mediator to go fetch more history from Firebase and save it to Room.
 * */

@OptIn(ExperimentalPagingApi::class)
class ChatRemoteMediator(
    private val channelId: String,
    private val currentUserId: String,
    private val recipientId: String,
    private val database: UserDatabase,
    private val server: ServerSide
) : RemoteMediator<Int, MessagesDTO>() {

    private val msgDao = database.messageDao()

    companion object {
        private const val TAG = "ChatRemoteMediator"
        private const val PAGE_SIZE = 20
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MessagesDTO>
    ): MediatorResult {
        return try {
            Log.d(TAG, "Loading: $loadType")

            val loadKey = when (loadType) {
                LoadType.REFRESH -> {
                    // Initial load - get the newest messages
                    null
                }
                LoadType.PREPEND -> {
                    // No newer messages via paging (real-time listener handles this)
                    Log.d(TAG, "PREPEND - end of pagination")
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.APPEND -> {
                    // Load older messages - get the oldest message timestamp we have
                    val lastItem = state.lastItemOrNull()
                    if (lastItem == null) {
                        Log.d(TAG, "APPEND - no items, end of pagination")
                        return MediatorResult.Success(endOfPaginationReached = true)
                    }
                    // Use the oldest message's timestamp for pagination
                    lastItem.firebaseTimestamp.takeIf { it > 0 } ?: lastItem.messageTime
                }
            }

            Log.d(TAG, "Loading from timestamp: $loadKey, pageSize: ${state.config.pageSize}")

            // Fetch from Firebase using timestamp-based pagination
            val messages = server.getMessagesByPage(
                currentUserId = currentUserId,
                recipientId = recipientId,
                lastTimestamp = loadKey,
                pageSize = state.config.pageSize
            )

            Log.d(TAG, "Fetched ${messages.size} messages from Firebase")

            database.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    // Optional: Clear old data on refresh
                    // Uncomment if you want fresh data on pull-to-refresh
                    // msgDao.clearMessages(channelId)
                }

                // Convert and save messages
                val entities = messages.map { msg ->
                    MessagesDTO(
                        messageId = msg.id,
                        messageText = msg.text,
                        senderId = msg.senderId,
                        recipientId = msg.recipientId,
                        senderName = msg.senderName,
                        recipientName = msg.recipientName,
                        messageTime = msg.date.time,
                        firebaseTimestamp = msg.date.time, // Use message date as Firebase timestamp
                        channelID = channelId,
                        messageType = msg.type,
                        status = if (msg.senderId == currentUserId) MessageStatus.SYNCED else MessageStatus.RECEIVED,
                        isRead = msg.isRead,
                        isDelivered = msg.isDelivered
                    )
                }

                // Use IGNORE strategy to prevent overwriting newer data from real-time listener
                msgDao.insertAllIgnoreExisting(entities)
                Log.d("TAG", "Inserted ${entities.size} messages to Room")
            }

            // Determine if we've reached the end
            val endOfPaginationReached = messages.isEmpty() || messages.size < state.config.pageSize
            Log.d(TAG, "End of pagination: $endOfPaginationReached")

            MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)

        } catch (e: Exception) {
            Log.e(TAG, "Error loading messages: ${e.message}", e)
            MediatorResult.Error(e)
        }
    }

    /**
     * Initialize is called before the first load
     * We can use this to check if we need to refresh
     */
    override suspend fun initialize(): InitializeAction {
        // Check if we have any local data
        val oldestMessage = msgDao.getOldestMessage(channelId)

        return if (oldestMessage == null) {
            // No local data - perform a fresh load
            Log.d(TAG, "Initialize: No local data, performing fresh load")
            InitializeAction.LAUNCH_INITIAL_REFRESH
        } else {
            // We have local data - skip initial refresh
            // Real-time listener will handle new messages
            Log.d(TAG, "Initialize: Local data exists, skipping initial refresh")
            InitializeAction.SKIP_INITIAL_REFRESH
        }
    }
}
