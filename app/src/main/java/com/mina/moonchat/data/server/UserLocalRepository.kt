package com.mina.moonchat.data.server

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
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
import com.mina.moonchat.models.MessageType
import com.mina.moonchat.utils.FcmNotificationSender
import com.mina.moonchat.utils.NotificationHelper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class UserLocalRepository(
    private val db: UserDatabase,
    private val server: ServerSide
) {
    private val msgDao = db.messageDao()
    private val mAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val imageWorkerClient by lazy { ChatImageWorkerClient() }
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val networkObserver by lazy {
        com.mina.moonchat.utils.NetworkObserver(com.mina.moonchat.application.MoonChat.instance) {
            Log.d(TAG, "Internet connection restored, auto-syncing queued pending/failed messages...")
            repositoryScope.launch {
                syncPendingMessages()
            }
        }
    }

    init {
        startNetworkObserver()
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

    // Track active listeners to prevent duplicates and race conditions
    @Volatile
    private var activeListener: ActiveListener? = null
    @Volatile
    private var requestedRecipientId: String? = null

    private data class ActiveListener(
        val currentUserId: String,
        val recipientId: String,
        val handle: ServerSide.MessageListenerHandle
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
     * Synchronously sets requestedRecipientId and cleans up previous listener
     */
    fun activateRealtimeSync(currentUserId: String, recipientId: String) {
        synchronized(this) {
            requestedRecipientId = recipientId
            removeActiveListener()
        }

        val channelId = getChannelId(currentUserId, recipientId)

        repositoryScope.launch {
            val newestMessage = msgDao.getNewestMessage(channelId)
            val fromTimestamp = (newestMessage?.firebaseTimestamp ?: System.currentTimeMillis()) - 10000

            Log.d(TAG, "Starting realtime sync from timestamp: $fromTimestamp for recipient: $recipientId")

            val handle = server.listenForNewMessagesHandle(
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

            synchronized(this@UserLocalRepository) {
                if (requestedRecipientId == recipientId) {
                    activeListener = ActiveListener(
                        currentUserId = currentUserId,
                        recipientId = recipientId,
                        handle = handle
                    )
                } else {
                    // Sync was cancelled while fetching newestMessage
                    server.removeMessageListener(handle)
                }
            }
        }
    }

    @Volatile
    private var globalMessageQuery: Query? = null
    @Volatile
    private var globalMessageListener: ValueEventListener? = null

    /**
     * Start listening for all incoming messages for the logged-in user across all channels.
     * Triggers notifications when the user receives messages while not in that specific chat.
     */
    fun startGlobalIncomingMessageListener() {
        val currentUserId = mAuth.currentUser?.uid ?: return

        synchronized(this) {
            if (globalMessageListener != null) return

            val userMessagesRef = FirebaseDatabase.getInstance()
                .getReference("Message")
                .child("Sender: $currentUserId")

            globalMessageQuery = userMessagesRef
            globalMessageListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    repositoryScope.launch {
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
    }

    fun stopGlobalIncomingMessageListener() {
        synchronized(this) {
            val listener = globalMessageListener
            val query = globalMessageQuery
            if (query != null && listener != null) {
                try {
                    query.removeEventListener(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing global message listener: ${e.message}")
                }
            }
            globalMessageListener = null
            globalMessageQuery = null
        }
    }

    /**
     * Stop real-time sync
     * Should be called when leaving a chat
     */
    fun stopRealtimeSync(currentUserId: String, recipientId: String) {
        synchronized(this) {
            if (requestedRecipientId == recipientId) {
                requestedRecipientId = null
            }
            removeActiveListener()
        }
        Log.d(TAG, "Realtime sync stopped for recipient: $recipientId")
    }

    private fun removeActiveListener() {
        activeListener?.let { active ->
            server.removeMessageListener(active.handle)
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
            firebaseTimestamp = originalFirebaseTimestamp,
            mediaJson = incoming.mediaJson ?: existing.mediaJson,
            localImagePath = incoming.localImagePath ?: existing.localImagePath,
            localImageMimeType = incoming.localImageMimeType ?: existing.localImageMimeType,
            localImageSize = incoming.localImageSize ?: existing.localImageSize,
            localImageWidth = incoming.localImageWidth ?: existing.localImageWidth,
            localImageHeight = incoming.localImageHeight ?: existing.localImageHeight
        )
    }

    private fun handleIncomingMessageUpdate(message: TextMessage) {
        repositoryScope.launch {
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
        repositoryScope.launch {
            try {
                if (message.senderId == currentUserId) {
                    Log.d(TAG, "Ignoring own message echo in handleIncomingMessage: ${message.id}")
                    return@launch
                }

                val channelId = getChannelId(message.senderId, message.recipientId)

                // Check if chat is open to mark as read immediately
                val activeRecipient = activeListener?.recipientId
                val globalActiveRecipient = com.mina.moonchat.utils.ActiveChatManager.activeRecipientId
                val isChatOpen = activeRecipient == message.senderId || globalActiveRecipient == message.senderId
                val finalIsRead = if (isChatOpen) true else message.isRead
                val finalIsDelivered = true

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

                Log.d(TAG, "New message saved: ${message.id}")
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

    /** Copies the picker URI into private storage, persists a stable message ID, then calls the Worker. */
    suspend fun sendImageMessage(
        context: Context,
        recipientId: String,
        recipientName: String,
        senderName: String,
        source: Uri
    ): TextMessage {
        val senderId = mAuth.currentUser?.uid ?: throw IllegalStateException("User not logged in")
        val messageId = createMessageId()
        val attachment = withContext(Dispatchers.IO) {
            ImageAttachmentStore.copyAndValidate(context.applicationContext, source)
        }
        val createdAt = System.currentTimeMillis()
        val channelId = getChannelId(senderId, recipientId)
        val pending = TextMessage(
            id = messageId,
            text = "",
            senderId = senderId,
            recipientId = recipientId,
            senderName = senderName,
            recipientName = recipientName,
            date = java.util.Date(createdAt),
            type = MessageType.IMAGE,
            channelId = channelId,
            status = MessageStatus.PENDING.name,
            localImagePath = attachment.file.absolutePath,
            localImageMimeType = attachment.mimeType,
            localImageSize = attachment.fileSizeBytes,
            localImageWidth = attachment.width,
            localImageHeight = attachment.height
        )
        val queued = pending.toMessagesDTO(MessageStatus.PENDING).copy(
            channelID = channelId,
            firebaseTimestamp = createdAt,
            localImagePath = attachment.file.absolutePath,
            localImageMimeType = attachment.mimeType,
            localImageSize = attachment.fileSizeBytes,
            localImageWidth = attachment.width,
            localImageHeight = attachment.height
        )
        try {
            msgDao.insertMessage(queued)
        } catch (error: Exception) {
            attachment.file.delete()
            throw error
        }

        if (!activeSyncingIds.add(messageId)) return queued.toTextMessage()
        return try {
            uploadAndStoreImage(queued)
            msgDao.getMessageById(messageId)?.toTextMessage() ?: pending.copy(status = MessageStatus.SENT.name)
        } catch (error: Exception) {
            Log.e(TAG, "uploadAndStoreImage failed for $messageId: ${error.javaClass.simpleName}: ${error.message}", error)
            markImageAttemptFailure(messageId, error)
            if (isPermanentImageError(error)) throw error
            Log.w(TAG, "Image $messageId queued for retry after a temporary upload failure: ${error.message}")
            msgDao.getMessageById(messageId)?.toTextMessage() ?: pending
        } finally {
            activeSyncingIds.remove(messageId)
        }
    }

    private suspend fun uploadAndStoreImage(message: MessagesDTO) {
        val mimeType = message.localImageMimeType
            ?: throw IllegalStateException("Queued image metadata is missing; select the image again.")
        val size = message.localImageSize
            ?: throw IllegalStateException("Queued image metadata is missing; select the image again.")
        val width = message.localImageWidth
            ?: throw IllegalStateException("Queued image metadata is missing; select the image again.")
        val height = message.localImageHeight
            ?: throw IllegalStateException("Queued image metadata is missing; select the image again.")
        val file = message.localImagePath?.let(::File)
        val result = imageWorkerClient.uploadAndFinalize(
            recipientId = message.recipientId,
            messageId = message.messageId,
            file = file,
            mimeType = mimeType,
            fileSizeBytes = size,
            width = width,
            height = height
        )
        msgDao.insertMessage(
            message.copy(
                messageTime = result.createdAt.time,
                firebaseTimestamp = result.createdAt.time,
                status = MessageStatus.SENT,
                retryCount = 0,
                lastAttemptTime = System.currentTimeMillis(),
                mediaJson = result.media.toJson(),
                localImagePath = null,
                localImageMimeType = null,
                localImageSize = null,
                localImageWidth = null,
                localImageHeight = null
            )
        )
        file?.delete()
    }

    private suspend fun markImageAttemptFailure(messageId: String, error: Exception) {
        val current = msgDao.getMessageById(messageId) ?: return
        val retryCount = current.retryCount + 1
        val status = if (isPermanentImageError(error) || retryCount >= MAX_RETRY_LIMIT) {
            MessageStatus.FAILED
        } else {
            MessageStatus.PENDING
        }
        msgDao.insertMessage(
            current.copy(
                status = status,
                retryCount = retryCount,
                lastAttemptTime = System.currentTimeMillis()
            )
        )
    }

    private fun isPermanentImageError(error: Exception): Boolean = when (error) {
        is WorkerRequestException -> error.isPermanent
        is IllegalStateException, is IllegalArgumentException, is SecurityException -> true
        is java.io.FileNotFoundException -> true
        is IOException -> error.message?.contains("saved image file", ignoreCase = true) == true
        else -> false
    }

    private suspend fun retryImageMessage(messageId: String) {
        val existing = msgDao.getMessageById(messageId)
            ?: throw IllegalStateException("The queued image is no longer available.")
        if (existing.messageType != MessageType.IMAGE) return
        if (!activeSyncingIds.add(messageId)) return
        try {
            val retry = existing.copy(
                status = MessageStatus.PENDING,
                retryCount = 0,
                lastAttemptTime = 0
            )
            msgDao.insertMessage(retry)
            uploadAndStoreImage(retry)
        } catch (error: Exception) {
            markImageAttemptFailure(messageId, error)
            if (isPermanentImageError(error)) throw error
            Log.w(TAG, "Image $messageId remains queued after retry")
        } finally {
            activeSyncingIds.remove(messageId)
        }
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

        if (message.type == MessageType.IMAGE) {
            repositoryScope.launch {
                try {
                    val queuedImage = msgDao.getMessageById(messageId)
                    if (queuedImage != null && queuedImage.messageType == MessageType.IMAGE) {
                        uploadAndStoreImage(queuedImage)
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Queued image $messageId could not be uploaded: ${error.message}", error)
                    markImageAttemptFailure(messageId, error)
                } finally {
                    activeSyncingIds.remove(messageId)
                }
            }
            return
        }

        repositoryScope.launch {
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
                        repositoryScope.launch {
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

                                server.updateChatChannels(
                                    senderId = message.senderId,
                                    senderName = message.senderName,
                                    senderProfileImg = senderProfileImg,
                                    recipientId = message.recipientId,
                                    recipientName = message.recipientName,
                                    recipientProfileImg = recipientProfileImg,
                                    lastMessage = message.text
                                )

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
                        repositoryScope.launch {
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
        if (message.type == MessageType.IMAGE) {
            retryImageMessage(message.id)
            return
        }
        val messageToRetry = message.copy(
            status = MessageStatus.PENDING.name,
            retryCount = 0,
            lastAttemptTime = 0
        )
        sendMessage(messageToRetry, senderProfileImg, recipientProfileImg)
    }

    suspend fun editMessage(message: TextMessage, newText: String) {
        val currentUserId = mAuth.currentUser?.uid ?: throw IllegalStateException("User not logged in")
        val nowMs = System.currentTimeMillis()

        if (!message.canBeEditedBy(currentUserId, nowMs)) {
            throw IllegalArgumentException("Message editing time limit expired (15 minutes) or unauthorized.")
        }

        val trimmedText = newText.trim()
        if (trimmedText.isBlank()) return

        val editedAtMs = nowMs

        // 1. Update local Room cache
        val existingDTO = msgDao.getMessageById(message.id)
        if (existingDTO != null) {
            val updatedDTO = existingDTO.copy(
                messageText = trimmedText,
                isEdited = true,
                editedAt = editedAtMs
            )
            msgDao.insertMessage(updatedDTO)
        }

        // 2. Update Firebase Realtime Database
        server.editMessageOnFirebase(
            senderId = message.senderId,
            recipientId = message.recipientId,
            messageId = message.id,
            newText = trimmedText,
            editedAtMs = editedAtMs,
            onSuccess = {
                repositoryScope.launch {
                    val newest = msgDao.getNewestMessage(message.channelId)
                    if (newest?.messageId == message.id) {
                        server.updateChatChannels(
                            senderId = message.senderId,
                            senderName = message.senderName,
                            senderProfileImg = null,
                            recipientId = message.recipientId,
                            recipientName = message.recipientName,
                            recipientProfileImg = null,
                            lastMessage = trimmedText
                        )
                    }
                }
            },
            onError = { e ->
                Log.e(TAG, "Failed to edit message on Firebase: ${e.message}")
            }
        )
    }

    suspend fun deleteMessageForEveryone(message: TextMessage) {
        val currentUserId = mAuth.currentUser?.uid ?: throw IllegalStateException("User not logged in")
        val nowMs = System.currentTimeMillis()

        if (!message.canBeDeletedForEveryoneBy(currentUserId, nowMs)) {
            throw IllegalArgumentException("Delete for everyone time limit expired (24 hours) or unauthorized.")
        }

        val tombstoneText = "This message was deleted"

        // 1. Update local Room cache
        val existingDTO = msgDao.getMessageById(message.id)
        if (existingDTO != null) {
            val deletedDTO = existingDTO.copy(
                messageText = tombstoneText,
                isDeletedForEveryone = true,
                mediaJson = null,
                localImagePath = null
            )
            msgDao.insertMessage(deletedDTO)
        }

        // 2. Update Firebase Realtime Database
        server.deleteMessageForEveryoneOnFirebase(
            senderId = message.senderId,
            recipientId = message.recipientId,
            messageId = message.id,
            tombstoneText = tombstoneText,
            onSuccess = {
                repositoryScope.launch {
                    val newest = msgDao.getNewestMessage(message.channelId)
                    if (newest?.messageId == message.id) {
                        server.updateChatChannels(
                            senderId = message.senderId,
                            senderName = message.senderName,
                            senderProfileImg = null,
                            recipientId = message.recipientId,
                            recipientName = message.recipientName,
                            recipientProfileImg = null,
                            lastMessage = tombstoneText
                        )
                    }
                }
            },
            onError = { e ->
                Log.e(TAG, "Failed to delete for everyone on Firebase: ${e.message}")
            }
        )
    }

    suspend fun deleteMessageForMe(message: TextMessage) {
        val currentUserId = mAuth.currentUser?.uid ?: throw IllegalStateException("User not logged in")

        // 1. Remove from local Room cache for current user
        msgDao.deleteMessageById(message.id)

        // 2. Remove node under Sender: $currentUserId path in Realtime Database
        server.deleteMessageForMeOnFirebase(
            currentUserId = currentUserId,
            recipientId = if (message.senderId == currentUserId) message.recipientId else message.senderId,
            messageId = message.id,
            onSuccess = {
                repositoryScope.launch {
                    val newest = msgDao.getNewestMessage(message.channelId)
                    val previewText = newest?.messageText ?: ""
                    server.updateOwnChatChannel(
                        ownerUserId = currentUserId,
                        otherUserId = if (message.senderId == currentUserId) message.recipientId else message.senderId,
                        otherUserName = if (message.senderId == currentUserId) message.recipientName else message.senderName,
                        lastMessage = previewText,
                        lastMessageSenderId = newest?.senderId ?: currentUserId
                    )
                }
            },
            onError = { e ->
                Log.e(TAG, "Failed to delete for me on Firebase: ${e.message}")
            }
        )
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
