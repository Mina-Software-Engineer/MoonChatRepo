package com.mina.moonchat.data.server

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.mina.moonchat.data.dto.MessageStatus
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.toMessagesDTO
import com.mina.moonchat.data.dto.toTextMessage
import com.mina.moonchat.data.local.UserDatabase
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.utils.FcmNotificationSender
import com.mina.moonchat.utils.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var activeListener: ActiveListener? = null

    private data class ActiveListener(
        val currentUserId: String,
        val recipientId: String,
        val listener: ChildEventListener
    )

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
            // Use a small buffer (10 seconds) to ensure we catch status updates for recent messages
            val fromTimestamp = (newestMessage?.firebaseTimestamp ?: System.currentTimeMillis()) - 10000

            Log.d("TAG", "Starting realtime sync from timestamp: $fromTimestamp")

            val listener = server.listenForNewMessages(
                currentUserId = currentUserId,
                recipientId = recipientId,
                fromTimestamp = fromTimestamp,
                onMessageChanged = { updatedMessage ->
                    handleIncomingMessageUpdate(updatedMessage)
                },
                onNewMessage = { newMessage ->
                    handleIncomingMessage(newMessage, currentUserId)
                }
            )

            activeListener = ActiveListener(
                currentUserId = currentUserId,
                recipientId = recipientId,
                listener = listener
            )
        }
    }

    private var globalMessageListener: ValueEventListener? = null

    /**
     * Start listening for all incoming messages for the logged-in user across all channels.
     * Triggers notifications when the user receives messages while not in that specific chat.
     */
    fun startGlobalIncomingMessageListener() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        if (globalMessageListener != null) return

        val userMessagesRef = FirebaseDatabase.getInstance()
            .getReference("Message")
            .child("Sender: $currentUserId")

        globalMessageListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                CoroutineScope(Dispatchers.IO).launch {
                    for (channelSnapshot in snapshot.children) {
                        val messagesSnapshot = channelSnapshot.child("Messages")
                        for (messageSnapshot in messagesSnapshot.children) {
                            val msg = messageSnapshot.getValue(TextMessage::class.java) ?: continue
                            msg.id = messageSnapshot.key ?: ""

                            val isRead = messageSnapshot.child("isRead").getValue(Boolean::class.java) ?: msg.isRead

                            if (msg.senderId != currentUserId && !isRead) {
                                handleIncomingMessage(msg, currentUserId)
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Global message listener cancelled: ${error.message}")
            }
        }

        userMessagesRef.addValueEventListener(globalMessageListener!!)
        Log.d(TAG, "Global incoming message listener started for user: $currentUserId")
    }

    fun stopGlobalIncomingMessageListener() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        globalMessageListener?.let {
            FirebaseDatabase.getInstance()
                .getReference("Message")
                .child("Sender: $currentUserId")
                .removeEventListener(it)
        }
        globalMessageListener = null
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
        activeListener?.let { active ->
            server.removeMessageListener(
                currentUserId = active.currentUserId,
                recipientId = active.recipientId,
                listener = active.listener
            )
        }
        activeListener = null
    }

    private fun handleIncomingMessageUpdate(message: TextMessage) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existing = msgDao.getMessageById(message.id) ?: return@launch
                
                // Update local flags from server
                val updated = existing.copy(
                    isRead = message.isRead,
                    isDelivered = message.isDelivered,
                    status = if (existing.status == MessageStatus.PENDING || existing.status == MessageStatus.FAILED) 
                        MessageStatus.SENT else existing.status
                )
                
                msgDao.insertAll(listOf(updated))
                Log.d(TAG, "Message status updated from server: ${message.id}, read=${message.isRead}, delivered=${message.isDelivered}")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming message update: ${e.message}")
            }
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

                // Check if chat is open to mark as read immediately
                val isChatOpen = activeListener?.recipientId == message.senderId
                val finalIsRead = if (isChatOpen) true else message.isRead
                val finalIsDelivered = true // Received on device means delivered

                // Update Firebase if we altered states locally (for recipient)
                if (message.senderId != currentUserId && (finalIsRead != message.isRead || finalIsDelivered != message.isDelivered)) {
                    server.updateMessageStatusOnFirebase(
                        senderId = message.senderId,
                        recipientId = message.recipientId,
                        messageId = message.id,
                        isRead = finalIsRead,
                        isDelivered = finalIsDelivered
                    )
                }

                // Check if message already exists
                val existingMessage = msgDao.getMessageById(message.id)

                if (existingMessage != null) {
                    // Update flags even if it's our own message (echo)
                    val updatedDTO = existingMessage.copy(
                        isRead = finalIsRead,
                        isDelivered = finalIsDelivered
                    )
                    msgDao.insertAll(listOf(updatedDTO))
                    Log.d(TAG, "Existing message updated: ${message.id}, read=$finalIsRead, delivered=$finalIsDelivered")
                    return@launch
                }

                // New message - Only continue if it's NOT an echo
                if (message.senderId == currentUserId) {
                    Log.d(TAG, "Ignoring new message echo: ${message.id}")
                    return@launch
                }

                val messageDTO = message.toMessagesDTO(MessageStatus.RECEIVED).copy(
                    channelID = channelId,
                    firebaseTimestamp = message.date.time,
                    isRead = finalIsRead,
                    isDelivered = finalIsDelivered
                )

                msgDao.insertMessage(messageDTO)
                server.updateOwnChatChannel(
                    ownerUserId = currentUserId,
                    otherUserId = message.senderId,
                    otherUserName = message.senderName,
                    lastMessage = message.text,
                    lastMessageSenderId = message.senderId,
                    timestamp = message.date.time
                )

                // Show notification if user is not in the chat with sender
                FirebaseFirestore.getInstance().collection("Users").document(message.senderId).get()
                    .addOnSuccessListener { doc ->
                        val pfp = doc.getString("profileImg") ?: doc.getString("pfp")
                        NotificationHelper.showNotification(
                            messageId = message.id,
                            senderId = message.senderId,
                            senderName = message.senderName,
                            senderProfileImg = pfp,
                            messageText = message.text
                        )
                    }
                    .addOnFailureListener {
                        NotificationHelper.showNotification(
                            messageId = message.id,
                            senderId = message.senderId,
                            senderName = message.senderName,
                            senderProfileImg = null,
                            messageText = message.text
                        )
                    }

                Log.d("TAG", "New message saved: ${message.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming message: ${e.message}")
            }
        }
    }

    /**
     * Send message with optimistic UI update
     */
    suspend fun sendMessage(
        message: TextMessage,
        senderProfileImg: String?,
        recipientProfileImg: String?
    ) {
        val channelId = getChannelId(message.senderId, message.recipientId)
        val messageId = message.id.takeIf { it.isNotBlank() } ?: server.createMessageId()
            ?: throw IllegalStateException("Failed to generate message ID")

        val pendingMessage = message.copy(
            id = messageId,
            status = "PENDING",
            isRead = false,
            isDelivered = false
        )

        // 2. Save to Room immediately (optimistic UI)
        val pendingDTO = pendingMessage.toMessagesDTO(MessageStatus.PENDING).copy(
            channelID = channelId,
            firebaseTimestamp = System.currentTimeMillis()
        )
        msgDao.insertMessage(pendingDTO)
        Log.d("TAG", "Pending message saved: $messageId")

        // 3. Send to Firebase
        server.sendMessage(
            message = pendingMessage,
            onSuccess = { firebaseId ->
                CoroutineScope(Dispatchers.IO).launch {
                    msgDao.updateMessageStatus(messageId, MessageStatus.SENT)
                    Log.d("TAG", "Pending message marked as sent: $firebaseId")

                    // Update chat channel metadata
                    server.updateChatChannels(
                        senderId = pendingMessage.senderId,
                        senderName = pendingMessage.senderName,
                        senderProfileImg = senderProfileImg,
                        recipientId = pendingMessage.recipientId,
                        recipientName = pendingMessage.recipientName,
                        recipientProfileImg = recipientProfileImg,
                        lastMessage = pendingMessage.text
                    )

                    // Trigger FCM push notification to recipient
                    FcmNotificationSender.sendNotification(
                        recipientId = pendingMessage.recipientId,
                        senderId = pendingMessage.senderId,
                        senderName = pendingMessage.senderName,
                        senderProfileImg = senderProfileImg,
                        messageText = pendingMessage.text,
                        messageId = messageId
                    )
                }
            },
            onError = { error ->
                CoroutineScope(Dispatchers.IO).launch {
                    msgDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    Log.e("TAG", "Failed to send message: ${error.message}")
                }
            }
        )
    }

    /**
     * Retry sending a failed message
     */
    suspend fun retryMessage(message: TextMessage) {
        msgDao.deleteMessageById(message.id)

        sendMessage(message.copy(status = "PENDING"), null, null)
    }

    /**
     * Mark messages as read
     */
    suspend fun markMessagesAsRead(channelId: String, currentUserId: String, recipientId: String) {
        try {
            val unread = msgDao.getUnreadMessages(channelId, currentUserId)
            unread.forEach { msg ->
                server.updateMessageStatusOnFirebase(
                    senderId = msg.senderId,
                    recipientId = msg.recipientId,
                    messageId = msg.messageId,
                    isRead = true,
                    isDelivered = true
                )
            }
            msgDao.markMessagesAsRead(channelId, currentUserId)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking messages as read: ${e.message}")
        }
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
