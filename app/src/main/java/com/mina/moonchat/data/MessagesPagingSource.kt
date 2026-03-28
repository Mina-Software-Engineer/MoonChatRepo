package com.mina.moonchat.data

import android.content.ContentValues.TAG
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.mina.moonchat.data.server.ServerSide
import com.mina.moonchat.models.TextMessage
import java.io.IOException

class MessagesPagingSource(
    private val server: ServerSide,
    private val currentUserId: String,
    private val recipientId: String,
) : PagingSource<Long, TextMessage>() { // Use Long for timestamps

    companion object {
        private const val PAGE_SIZE = 20
    }

    /**
     * Get the refresh key for the current state.
     * Returns the timestamp of the message closest to the anchor position.
     */
    override fun getRefreshKey(state: PagingState<Long, TextMessage>): Long? {
        return state.anchorPosition?.let { position ->
            // Get the timestamp of the message closest to the anchor position
            state.closestItemToPosition(position)?.date?.time
        }
    }

    /**
     * Load a page of messages.
     *
     * @param params.key The timestamp of the oldest message from the previous page (null for first page)
     */
    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, TextMessage> {
        val lastTimestamp = params.key // Timestamp of the oldest loaded message (or null for first page)

        return try {
            Log.d(TAG, "Loading page with lastTimestamp: $lastTimestamp, loadSize: ${params.loadSize}")

            // Fetch messages for the current page using timestamp-based pagination
            val response = server.getMessagesByPage(
                currentUserId = currentUserId,
                recipientId = recipientId,
                lastTimestamp = lastTimestamp,
                pageSize = params.loadSize.coerceAtMost(PAGE_SIZE)
            )

            Log.d(TAG, "Fetched ${response.size} messages from Firebase")

            // Determine the key for the next page (older messages)
            // Use the timestamp of the oldest message in this page
            val nextKey = if (response.isNotEmpty()) {
                // Find the oldest message (smallest timestamp)
                response.minByOrNull { it.date.time }?.date?.time
            } else {
                null // No more data to load
            }

            // Determine the key for the previous page (newer messages)
            // Use the timestamp of the newest message in this page
            val prevKey = if (response.isNotEmpty() && lastTimestamp != null) {
                // Find the newest message (largest timestamp)
                response.maxByOrNull { it.date.time }?.date?.time
            } else {
                null // No previous page (we're at the newest messages)
            }

            Log.d(TAG, "nextKey (older): $nextKey, prevKey (newer): $prevKey")

            LoadResult.Page(
                data = response,
                prevKey = prevKey, // Key to load newer messages (for prepend)
                nextKey = nextKey  // Key to load older messages (for append)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error loading messages: ${e.message}", e)
            LoadResult.Error(e)
        }
    }
}
