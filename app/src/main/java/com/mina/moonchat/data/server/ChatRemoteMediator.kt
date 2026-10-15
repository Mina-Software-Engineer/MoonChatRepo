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

            val (loadTimestamp, loadKey) = when (loadType) {
                LoadType.REFRESH -> null to null
                LoadType.PREPEND -> {
                    Log.d(TAG, "PREPEND - end of pagination")
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.APPEND -> {
                    val oldestItem = state.lastItemOrNull()
                    if (oldestItem == null) {
                        Log.d(TAG, "APPEND - no items, end of pagination")
                        return MediatorResult.Success(endOfPaginationReached = true)
                    }
                    val ts = oldestItem.firebaseTimestamp.takeIf { it > 0 } ?: oldestItem.messageTime
                    ts to oldestItem.messageId
                }
            }

            Log.d(TAG, "Loading from compound boundary: timestamp=$loadTimestamp, key=$loadKey, pageSize=${state.config.pageSize}")

            // Fetch from Firebase using compound (timestamp, key) boundary pagination
            val messages = server.getMessagesByPage(
                currentUserId = currentUserId,
                recipientId = recipientId,
                lastTimestamp = loadTimestamp,
                lastKey = loadKey,
                pageSize = state.config.pageSize
            )

            Log.d(TAG, "Fetched ${messages.size} messages from Firebase")

            database.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    // Optional: Clear old data on refresh
                }

                // Convert and save messages
                val entities = messages.map { msg ->
                    val existing = msgDao.getMessageById(msg.id)
                    val fetchedDTO = MessagesDTO(
                        messageId = msg.id,
                        messageText = msg.text,
                        senderId = msg.senderId,
                        recipientId = msg.recipientId,
                        senderName = msg.senderName,
                        recipientName = msg.recipientName,
                        messageTime = msg.date.time,
                        firebaseTimestamp = msg.date.time,
                        channelID = channelId,
                        messageType = msg.type,
                        status = if (msg.senderId == currentUserId) MessageStatus.SYNCED else MessageStatus.RECEIVED,
                        isRead = msg.isRead,
                        isDelivered = msg.isDelivered,
                        mediaJson = msg.media?.toJson()
                    )
                    if (existing != null) {
                        existing.copy(
                            isRead = existing.isRead || msg.isRead,
                            isDelivered = existing.isDelivered || msg.isDelivered,
                            mediaJson = existing.mediaJson ?: fetchedDTO.mediaJson
                        )
                    } else {
                        fetchedDTO
                    }
                }

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
