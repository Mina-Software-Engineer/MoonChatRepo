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
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.data.dto.MessageStatus
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.UserInfoDTO
import com.mina.moonchat.data.dto.toMessagesDTO
import com.mina.moonchat.data.dto.toTextMessage
import com.mina.moonchat.data.local.UserDatabase
import com.mina.moonchat.intro_activity.data.Result
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.utils.FcmNotificationSender
import com.mina.moonchat.utils.NotificationHelper
import java.util.concurrent.ConcurrentHashMap
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

    private val networkObserver by lazy {
        com.mina.moonchat.utils.NetworkObserver(com.mina.moonchat.application.MoonChat.instance) {
            Log.d(TAG, "Internet connection restored, auto-syncing queued pending/failed messages...")
            CoroutineScope(Dispatchers.IO).launch {
                syncPendingMessages()
            }
        }
    }

    fun startNetworkObserver() {
        try {
            networkObserver.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting network observer: ${e.message}")
        }
    }

    fun stopNetworkObserver() {
        try {
            networkObserver.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping network observer: ${e.message}")
        }
    }

    // Auth & User DAO Methods
    suspend fun getCurrentUserInfo(): Result<AuthUserDTO> {
        return try {
            val user = db.authDao().getCurrentUserInfo()
            Result.Success(
                AuthUserDTO(
                    user.userId,
                    user.username,
                    user.email,
                    user.password,
                    user.pfp,
                    user.onlineStatus,
                    user.bio,
                    user.id
                )
            )
        } catch (ex: Exception) {
            Result.Error(ex.localizedMessage ?: "Unknown error")
        }
    }

    suspend fun addCurrentUserInfo(currentUser: AuthUserDTO) {
        db.authDao().addCurrentUserInfo(currentUser)
    }

    suspend fun deleteCurrentUserInfo() {
        db.authDao().deleteCurrentUserInfo()
    }

    suspend fun getUsersInfo(): Result<List<UserInfoDTO>> {
        return try {
            Result.Success(db.userDao().getChatsList())
        } catch (ex: Exception) {
            Result.Error(ex.localizedMessage ?: "Unknown error")
        }
    }

    suspend fun insertNewUser(user: UserInfoDTO) {
        db.userDao().saveChat(user)
    }

    companion object {
        private const val TAG = "UserLocalRepository"
        private const val PAGE_SIZE = 20
        private const val MAX_RETRY_LIMIT = 5
    }

    private val activeSyncingIds = ConcurrentHashMap.newKeySet<String>()

    private fun isPermanentError(error: Exception): Boolean {
        val message = error.message?.lowercase() ?: ""
        return message.contains("permission_denied") ||
               message.contains("permission denied") ||
               message.contains("unauthorized") ||
               message.contains("invalid key")
    }

    private fun calculateBackoffDelay(retryCount: Int): Long {
        if (retryCount <= 0) return 0L
        val exponent = minOf(retryCount, 5)
        return 1000L * (1 shl exponent)
    }

    // Track active listeners to prevent duplicates
    private var activeListener: ActiveListener? = null

    private data class ActiveListener(
        val currentUserId: String,
        val recipientId: String,
        val listener: ChildEventListener
    )

    /**
     * Generate unique message ID
     */
    fun createMessageId(): String = server.createMessageId() ?: java.util.UUID.randomUUID().toString()

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

    fun mergeMessageDTO(
        existing: MessagesDTO,
        incoming: MessagesDTO,
        isExplicitLocalAction: Boolean = false
    ): MessagesDTO {
        val finalIsRead = existing.isRead || incoming.isRead
        val finalIsDelivered = existing.isDelivered || incoming.isDelivered

        val finalStatus = if (isExplicitLocalAction) {
            incoming.status
        } else {
            when (existing.status) {
                MessageStatus.SENT, MessageStatus.SYNCED, MessageStatus.RECEIVED -> {
                    if (incoming.status == MessageStatus.PENDING || incoming.status == MessageStatus.FAILED) {
                        existing.status
                    } else {
                        incoming.status
                    }
                }
                else -> incoming.status
            }
        }

        val originalTime = if (existing.messageTime > 0) existing.messageTime else incoming.messageTime
        val originalFirebaseTimestamp = if (existing.firebaseTimestamp > 0) existing.firebaseTimestamp else incoming.firebaseTimestamp

        return incoming.copy(
            status = finalStatus,
            isRead = finalIsRead,
            isDelivered = finalIsDelivered,
            messageTime = originalTime,
            firebaseTimestamp = originalFirebaseTimestamp
        )
    }

    private fun handleIncomingMessageUpdate(message: TextMessage) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existing = msgDao.getMessageById(message.id) ?: return@launch

                val incomingDTO = message.toMessagesDTO(
                    if (existing.status == MessageStatus.PENDING || existing.status == MessageStatus.FAILED)
                        MessageStatus.SENT else existing.status
                )
                val merged = mergeMessageDTO(existing, incomingDTO)

                msgDao.insertAll(listOf(merged))
                Log.d(TAG, "Message status updated from server: ${message.id}, read=${merged.isRead}, delivered=${merged.isDelivered}")
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
                // Ignore own sent messages (echos from Firebase) on the sender's device.
                // Delivery and Read statuses are only updated by the recipient's device.
                if (message.senderId == currentUserId) {
                    Log.d(TAG, "Ignoring own message echo in handleIncomingMessage: ${message.id}")
                    return@launch
                }

                val channelId = getChannelId(message.senderId, message.recipientId)

                // Check if chat is open to mark as read immediately
                val isChatOpen = activeListener?.recipientId == message.senderId || com.mina.moonchat.utils.ActiveChatManager.activeRecipientId == message.senderId
                val finalIsRead = if (isChatOpen) true else message.isRead
                val finalIsDelivered = true // Received on recipient's device means delivered

                // Update Firebase if we altered states locally (for recipient)
                if (finalIsRead != message.isRead || finalIsDelivered != message.isDelivered) {
                    server.updateMessageStatusOnFirebase(
                        senderId = message.senderId,
                        recipientId = message.recipientId,
                        messageId = message.id,
                        isRead = finalIsRead,
                        isDelivered = finalIsDelivered
                    )
                }

                val incomingDTO = message.toMessagesDTO(MessageStatus.RECEIVED).copy(
                    channelID = channelId,
                    firebaseTimestamp = message.date.time,
                    isRead = finalIsRead,
                    isDelivered = finalIsDelivered
                )

                val existingMessage = msgDao.getMessageById(message.id)
                val messageToSave = if (existingMessage != null) {
                    mergeMessageDTO(existingMessage, incomingDTO)
                } else {
                    incomingDTO
                }

                msgDao.insertMessage(messageToSave)
                server.updateOwnChatChannel(
                    ownerUserId = currentUserId,
                    otherUserId = message.senderId,
                    otherUserName = message.senderName,
                    lastMessage = message.text,
                    lastMessageSenderId = message.senderId,
                    timestamp = message.date.time
                )

                // Show notification if user is not in the chat with sender
                if (!isChatOpen) {
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
                }

                Log.d("TAG", "New message saved: ${message.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming message: ${e.message}")
            }
        }
    }

    /**
     * Send message with optimistic UI update and offline queuing
     */
    suspend fun sendMessage(
        message: TextMessage,
        senderProfileImg: String?,
        recipientProfileImg: String?
    ) {
        val channelId = getChannelId(message.senderId, message.recipientId)
        val messageId = message.id.takeIf { it.isNotBlank() } ?: server.createMessageId()
            ?: throw IllegalStateException("Failed to generate message ID")

        val isOnline = networkObserver.isNetworkConnected()
        val initialStatus = MessageStatus.PENDING

        val pendingMessage = message.copy(
            id = messageId,
            status = initialStatus.name,
            isRead = message.isRead,
            isDelivered = message.isDelivered,
            retryCount = message.retryCount,
            lastAttemptTime = if (message.lastAttemptTime > 0) message.lastAttemptTime else System.currentTimeMillis()
        )

        // Save to Room immediately (optimistic UI)
        val pendingDTO = pendingMessage.toMessagesDTO(initialStatus).copy(
            channelID = channelId,
            firebaseTimestamp = if (pendingMessage.date.time > 0) pendingMessage.date.time else System.currentTimeMillis()
        )

        val existing = msgDao.getMessageById(messageId)
        val messageToSave = if (existing != null) {
            mergeMessageDTO(existing, pendingDTO, isExplicitLocalAction = true)
        } else {
            pendingDTO
        }

        msgDao.insertMessage(messageToSave)
        Log.d(TAG, "Message saved locally: $messageId, initialStatus=$initialStatus, isOnline=$isOnline")

        if (!isOnline) {
            Log.w(TAG, "Device is offline. Message $messageId saved as PENDING in queue for auto-resend when reconnected.")
            return
        }

        attemptRemoteSend(pendingMessage, senderProfileImg, recipientProfileImg)
    }

    private fun attemptRemoteSend(
        message: TextMessage,
        senderProfileImg: String?,
        recipientProfileImg: String?
    ) {
        val messageId = message.id
        if (!activeSyncingIds.add(messageId)) {
            Log.d(TAG, "Message $messageId is already being processed. Skipping concurrent attempt.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existing = msgDao.getMessageById(messageId)
                val currentRetryCount = existing?.retryCount ?: message.retryCount

                if (message.recipientId.isBlank() || message.senderId.isBlank()) {
                    Log.e(TAG, "Permanent failure: invalid sender or recipient ID for message $messageId")
                    msgDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    activeSyncingIds.remove(messageId)
                    return@launch
                }

                server.sendMessage(
                    message = message,
                    onSuccess = { firebaseId ->
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val current = msgDao.getMessageById(messageId)
                                if (current != null) {
                                    val sentDTO = current.copy(
                                        status = MessageStatus.SENT,
                                        retryCount = 0,
                                        lastAttemptTime = System.currentTimeMillis()
                                    )
                                    msgDao.insertAll(listOf(sentDTO))
                                } else {
                                    msgDao.updateMessageStatus(messageId, MessageStatus.SENT)
                                }
                                Log.d(TAG, "Message marked as SENT: $firebaseId")

                                // Update chat channel metadata
                                server.updateChatChannels(
                                    senderId = message.senderId,
                                    senderName = message.senderName,
                                    senderProfileImg = senderProfileImg,
                                    recipientId = message.recipientId,
                                    recipientName = message.recipientName,
                                    recipientProfileImg = recipientProfileImg,
                                    lastMessage = message.text
                                )

                                // Trigger FCM push notification to recipient
                                FcmNotificationSender.sendNotification(
                                    recipientId = message.recipientId,
                                    senderId = message.senderId,
                                    senderName = message.senderName,
                                    senderProfileImg = senderProfileImg,
                                    messageText = message.text,
                                    messageId = messageId
                                )
                            } finally {
                                activeSyncingIds.remove(messageId)
                            }
                        }
                    },
                    onError = { error ->
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val current = msgDao.getMessageById(messageId)
                                val newRetryCount = (current?.retryCount ?: currentRetryCount) + 1
                                val isPermanent = isPermanentError(error)

                                if (isPermanent || newRetryCount >= MAX_RETRY_LIMIT) {
                                    Log.e(TAG, "Message $messageId failed permanently or reached max retry limit ($newRetryCount/$MAX_RETRY_LIMIT): ${error.message}")
                                    if (current != null) {
                                        val failedDTO = current.copy(
                                            status = MessageStatus.FAILED,
                                            retryCount = newRetryCount,
                                            lastAttemptTime = System.currentTimeMillis()
                                        )
                                        msgDao.insertAll(listOf(failedDTO))
                                    } else {
                                        msgDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                                    }
                                } else {
                                    Log.w(TAG, "Message $messageId transient send failure (attempt $newRetryCount/$MAX_RETRY_LIMIT): ${error.message}")
                                    if (current != null) {
                                        val pendingDTO = current.copy(
                                            status = MessageStatus.PENDING,
                                            retryCount = newRetryCount,
                                            lastAttemptTime = System.currentTimeMillis()
                                        )
                                        msgDao.insertAll(listOf(pendingDTO))
                                    }
                                }
                            } finally {
                                activeSyncingIds.remove(messageId)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                activeSyncingIds.remove(messageId)
                Log.e(TAG, "Exception during attemptRemoteSend for $messageId: ${e.message}")
            }
        }
    }

    /**
     * Retry sending a failed message
     */
    suspend fun retryMessage(
        message: TextMessage,
        senderProfileImg: String? = null,
        recipientProfileImg: String? = null
    ) {
        val messageToRetry = message.copy(
            status = MessageStatus.PENDING.name,
            retryCount = 0,
            lastAttemptTime = 0
        )
        sendMessage(messageToRetry, senderProfileImg, recipientProfileImg)
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
     * Sync pending messages on app startup or network reconnect
     */
    suspend fun syncPendingMessages() {
        if (!networkObserver.isNetworkConnected()) {
            Log.d(TAG, "syncPendingMessages skipped: Network is offline")
            return
        }

        val pendingMessages = msgDao.getPendingMessages()
        Log.d(TAG, "Found ${pendingMessages.size} pending messages to sync")

        val now = System.currentTimeMillis()

        pendingMessages.forEach { dto ->
            if (activeSyncingIds.contains(dto.messageId)) return@forEach

            if (dto.retryCount >= MAX_RETRY_LIMIT) {
                Log.w(TAG, "Message ${dto.messageId} reached max retry limit (${dto.retryCount}). Marking as FAILED.")
                msgDao.updateMessageStatus(dto.messageId, MessageStatus.FAILED)
                return@forEach
            }

            val backoffDelay = calculateBackoffDelay(dto.retryCount)
            if (dto.lastAttemptTime > 0 && (now - dto.lastAttemptTime) < backoffDelay) {
                Log.d(TAG, "Message ${dto.messageId} backing off for ${backoffDelay - (now - dto.lastAttemptTime)}ms")
                return@forEach
            }

            val message = dto.toTextMessage()
            attemptRemoteSend(message, null, null)
        }
    }
}
