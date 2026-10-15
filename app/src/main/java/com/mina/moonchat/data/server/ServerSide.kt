package com.mina.moonchat.data.server

import android.content.ContentValues
import android.content.ContentValues.TAG
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.mina.moonchat.models.ChatItem
import com.mina.moonchat.models.TextMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ServerSide {

    private val realtimeDatabase = FirebaseDatabase.getInstance()
    private val messagesRef = realtimeDatabase.getReference("Message")
    private val firestore = FirebaseFirestore.getInstance()

    fun createMessageId(): String? = messagesRef.push().key

    /**
     * Generate consistent channel ID - always sorted to ensure consistency
     * This ensures both users see the same channel
     */
    fun getChatChannelId(userId1: String, userId2: String): String {
        return if (userId1 < userId2) "${userId1}_${userId2}" else "${userId2}_${userId1}"
    }

    /**
     * Send message to Firebase
     * - Generates a unique message ID
     * - Saves to both sender and recipient paths for redundancy
     * - Uses server timestamp for consistent ordering
     */
    fun sendMessage(
        message: TextMessage,
        onSuccess: (String) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        val senderId = message.senderId
        val recipientId = message.recipientId
        val channelId = getChatChannelId(senderId, recipientId)

        // Generate unique message ID
        val messageKey = message.id.takeIf { it.isNotBlank() } ?: messagesRef.push().key ?: run {
            onError(Exception("Failed to generate message key"))
            return
        }

        // Preserve original message creation date if set
        val messageDate = if (message.date.time > 0) message.date.time else System.currentTimeMillis()

        // Create message data
        val messageData = hashMapOf(
            "id" to messageKey,
            "text" to message.text,
            "senderId" to message.senderId,
            "recipientId" to message.recipientId,
            "senderName" to message.senderName,
            "recipientName" to message.recipientName,
            "date" to messageDate,
            "type" to message.type,
            "channelId" to channelId,
            "isRead" to message.isRead,
            "isDelivered" to message.isDelivered
        )

        // Perform root-level atomic multi-location update to RTDB
        val updates = hashMapOf<String, Any>(
            "Sender: $senderId/ChannelID: $channelId/Messages/$messageKey" to messageData,
            "Sender: $recipientId/ChannelID: $channelId/Messages/$messageKey" to messageData
        )

        messagesRef.updateChildren(updates)
            .addOnSuccessListener {
                Log.d(TAG, "Message sent atomically to RTDB: $messageKey")
                onSuccess(messageKey)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed atomic multi-path write for message $messageKey", e)
                onError(e)
            }
    }

    /**
     * Get messages by page using compound (timestamp, key) boundary pagination
     * This avoids skipping or duplicating messages when equal timestamps exist
     */
    suspend fun getMessagesByPage(
        currentUserId: String,
        recipientId: String,
        lastTimestamp: Long?,
        lastKey: String? = null,
        pageSize: Int
    ): List<TextMessage> = suspendCoroutine { continuation ->
        val channelId = getChatChannelId(currentUserId, recipientId)

        val query = if (lastTimestamp == null || lastTimestamp == 0L || lastKey.isNullOrBlank()) {
            // First load - get the most recent messages
            messagesRef
                .child("Sender: $currentUserId")
                .child("ChannelID: $channelId")
                .child("Messages")
                .orderByChild("date")
                .limitToLast(pageSize)
        } else {
            // Load older messages (at or before compound boundary)
            messagesRef
                .child("Sender: $currentUserId")
                .child("ChannelID: $channelId")
                .child("Messages")
                .orderByChild("date")
                .endAt(lastTimestamp.toDouble(), lastKey)
                .limitToLast(pageSize + 1)
        }

        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<TextMessage>()
                for (messageSnapshot in snapshot.children) {
                    messageSnapshot.getValue(TextMessage::class.java)?.let { message ->
                        message.id = messageSnapshot.key ?: ""
                        // Deduplicate boundary cursor row if returned
                        if (message.id != lastKey) {
                            messages.add(message)
                        }
                    }
                }
                // Sort by date ascending for display
                messages.sortBy { it.date.time }
                val result = if (messages.size > pageSize) messages.takeLast(pageSize) else messages
                continuation.resume(result)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error fetching messages: ${error.message}")
                continuation.resume(emptyList())
            }
        })
    }

    /**
     * Listen for new messages as a Flow
     * This provides better lifecycle management
     */
    fun listenForNewMessagesFlow(
        currentUserId: String,
        recipientId: String,
        fromTimestamp: Long = 0
    ): Flow<TextMessage> = callbackFlow {
        val channelId = getChatChannelId(currentUserId, recipientId)

        val query = messagesRef
            .child("Sender: $currentUserId")
            .child("ChannelID: $channelId")
            .child("Messages")
            .orderByChild("date")
            .startAt(fromTimestamp.toDouble())

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(TextMessage::class.java)?.let { message ->
                    message.id = snapshot.key ?: ""
                    trySend(message)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                // Handle message updates (e.g., read status)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                // Handle message deletion
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled: ${error.message}")
                close(error.toException())
            }
        }

        query.addChildEventListener(listener)

        awaitClose {
            query.removeEventListener(listener)
            Log.d(TAG, "Message listener removed for channel: $channelId")
        }
    }

    data class MessageListenerHandle(
        val query: com.google.firebase.database.Query,
        val listener: ChildEventListener
    )

    /**
     * Listen for new messages returning a handle holding the exact query and listener
     * for accurate lifecycle cleanup.
     */
    fun listenForNewMessagesHandle(
        currentUserId: String,
        recipientId: String,
        fromTimestamp: Long = 0,
        onMessageChanged: (TextMessage) -> Unit = {},
        onNewMessage: (TextMessage) -> Unit
    ): MessageListenerHandle {
        val channelId = getChatChannelId(currentUserId, recipientId)

        val query = messagesRef
            .child("Sender: $currentUserId")
            .child("ChannelID: $channelId")
            .child("Messages")
            .orderByChild("date")
            .startAt(fromTimestamp.toDouble())

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(TextMessage::class.java)?.let { message ->
                    message.id = snapshot.key ?: ""
                    onNewMessage(message)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(TextMessage::class.java)?.let { message ->
                    message.id = snapshot.key ?: ""
                    onMessageChanged(message)
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled: ${error.message}")
            }
        }

        query.addChildEventListener(listener)
        return MessageListenerHandle(query, listener)
    }

    /**
     * Legacy listener method for compatibility
     */
    fun listenForNewMessages(
        currentUserId: String,
        recipientId: String,
        fromTimestamp: Long = 0,
        onMessageChanged: (TextMessage) -> Unit = {},
        onNewMessage: (TextMessage) -> Unit
    ): ChildEventListener {
        return listenForNewMessagesHandle(
            currentUserId = currentUserId,
            recipientId = recipientId,
            fromTimestamp = fromTimestamp,
            onMessageChanged = onMessageChanged,
            onNewMessage = onNewMessage
        ).listener
    }

    /**
     * Remove message listener using exact query handle
     */
    fun removeMessageListener(handle: MessageListenerHandle) {
        try {
            handle.query.removeEventListener(handle.listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove message listener: ${e.message}")
        }
    }

    /**
     * Legacy remove message listener
     */
    fun removeMessageListener(
        currentUserId: String,
        recipientId: String,
        listener: ChildEventListener
    ) {
        val channelId = getChatChannelId(currentUserId, recipientId)
        messagesRef
            .child("Sender: $currentUserId")
            .child("ChannelID: $channelId")
            .child("Messages")
            .removeEventListener(listener)
    }

    /**
     * Mark message as read
     */
    fun markMessageAsRead(
        currentUserId: String,
        recipientId: String,
        messageId: String
    ) {
        val channelId = getChatChannelId(currentUserId, recipientId)
        messagesRef
            .child("Sender: $currentUserId")
            .child("ChannelID: $channelId")
            .child("Messages")
            .child(messageId)
            .child("isRead")
            .setValue(true)
    }

    fun updateMessageStatusOnFirebase(
        senderId: String,
        recipientId: String,
        messageId: String,
        isRead: Boolean,
        isDelivered: Boolean
    ) {
        val channelId = getChatChannelId(senderId, recipientId)
        val updates = hashMapOf<String, Any>(
            "Sender: $senderId/ChannelID: $channelId/Messages/$messageId/isRead" to isRead,
            "Sender: $senderId/ChannelID: $channelId/Messages/$messageId/isDelivered" to isDelivered,
            "Sender: $recipientId/ChannelID: $channelId/Messages/$messageId/isRead" to isRead,
            "Sender: $recipientId/ChannelID: $channelId/Messages/$messageId/isDelivered" to isDelivered
        )
        messagesRef.updateChildren(updates).addOnFailureListener { e ->
            Log.e(TAG, "Failed atomic status update on Firebase for message $messageId", e)
        }
    }

    /**
     * Get chat list from Firestore
     */
    fun getChatsListFromServer(userId: String, callback: (List<ChatItem>) -> Unit) {
        val chatsCollection = firestore
            .collection("Users")
            .document(userId)
            .collection("chat channel")

        chatsCollection
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error fetching chats: ${error.message}")
                    callback(emptyList())
                    return@addSnapshotListener
                }

                val chatList = snapshots?.documents?.mapNotNull { document ->
                    val recipientId = document.getString("recipientId") ?: return@mapNotNull null
                    ChatItem(
                        chatId = document.id,
                        recipientId = recipientId,
                        username = document.getString("recipientName") ?: "Unknown",
                        lastMessage = document.getString("lastMessage") ?: "",
                        time = document.getTimestamp("date")?.toDate()?.time.toString(),
                        onlineState = document.getBoolean("isOnline") ?: false,
                        profileImg = document.getString("profileImg") ?: ""
                    )
                } ?: emptyList()

                callback(chatList)
            }
    }

    /**
     * Update or create chat channel metadata safely without overwriting newer previews
     */
    fun updateChatChannels(
        senderId: String,
        senderName: String,
        senderProfileImg: String?,
        recipientId: String,
        recipientName: String,
        recipientProfileImg: String?,
        lastMessage: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val channelId = getChatChannelId(senderId, recipientId)
        val newDate = Date(timestamp)

        fun updateChannelDoc(
            ownerId: String,
            otherId: String,
            otherName: String,
            otherProfileImg: String?,
            msgSenderId: String
        ) {
            val docRef = firestore
                .collection("Users")
                .document(ownerId)
                .collection("chat channel")
                .document(channelId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val existingDate = snapshot.getTimestamp("date")?.toDate()

                if (existingDate == null || newDate >= existingDate) {
                    val channelData = hashMapOf(
                        "recipientId" to otherId,
                        "recipientName" to otherName,
                        "profileImg" to otherProfileImg,
                        "lastMessage" to lastMessage,
                        "lastMessageSenderId" to msgSenderId,
                        "lastMessageRead" to false,
                        "date" to newDate,
                        "isOnline" to false
                    )
                    transaction.set(docRef, channelData, SetOptions.merge())
                } else {
                    Log.d(TAG, "Skipping Firestore metadata update for $ownerId: existing date $existingDate is newer than $newDate")
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Error updating Firestore chat channel for $ownerId: ${e.message}")
            }
        }

        updateChannelDoc(senderId, recipientId, recipientName, recipientProfileImg, senderId)
        updateChannelDoc(recipientId, senderId, senderName, senderProfileImg, senderId)
    }

    fun updateOwnChatChannel(
        ownerUserId: String,
        otherUserId: String,
        otherUserName: String,
        lastMessage: String,
        lastMessageSenderId: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val channelId = getChatChannelId(ownerUserId, otherUserId)
        val newDate = Date(timestamp)
        val docRef = firestore
            .collection("Users")
            .document(ownerUserId)
            .collection("chat channel")
            .document(channelId)

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            val existingDate = snapshot.getTimestamp("date")?.toDate()

            if (existingDate == null || newDate >= existingDate) {
                val channelData = hashMapOf(
                    "recipientId" to otherUserId,
                    "recipientName" to otherUserName,
                    "lastMessage" to lastMessage,
                    "lastMessageSenderId" to lastMessageSenderId,
                    "lastMessageRead" to false,
                    "date" to newDate
                )
                transaction.set(docRef, channelData, SetOptions.merge())
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Error updating own chat channel: ${e.message}")
        }
    }
}
