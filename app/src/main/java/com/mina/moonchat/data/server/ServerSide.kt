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
import com.mina.moonchat.models.longToDate
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

        // Create message data with server timestamp
        val messageData = hashMapOf(
            "id" to messageKey,
            "text" to message.text,
            "senderId" to message.senderId,
            "recipientId" to message.recipientId,
            "senderName" to message.senderName,
            "recipientName" to message.recipientName,
            "date" to longToDate(System.currentTimeMillis()), // Use server timestamp
            "type" to message.type,
            "channelId" to channelId,
            "isRead" to false
        )

        // Save to sender's path
        val senderPath = messagesRef
            .child("Sender: $senderId")
            .child("ChannelID: $channelId")
            .child("Messages")
            .child(messageKey)

        // Save to recipient's path
        val recipientPath = messagesRef
            .child("Sender: $recipientId")
            .child("ChannelID: $channelId")
            .child("Messages")
            .child(messageKey)

        // Execute both writes
        senderPath.setValue(messageData)
            .addOnSuccessListener {
                recipientPath.setValue(messageData)
                    .addOnSuccessListener {
                        Log.d(TAG, "Message sent successfully: $messageKey")
                        onSuccess(messageKey)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to send to recipient path", e)
                        onError(e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send to sender path", e)
                onError(e)
            }
    }

    /**
     * Get messages by page using timestamp-based pagination
     * This is more reliable than key-based pagination
     */
    suspend fun getMessagesByPage(
        currentUserId: String,
        recipientId: String,
        lastTimestamp: Long?, // Use timestamp instead of key for pagination
        pageSize: Int
    ): List<TextMessage> = suspendCoroutine { continuation ->
        val channelId = getChatChannelId(currentUserId, recipientId)

        val query = if (lastTimestamp == null || lastTimestamp == 0L) {
            // First load - get the most recent messages
            messagesRef
                .child("Sender: $currentUserId")
                .child("ChannelID: $channelId")
                .child("Messages")
                .orderByChild("date")
                .limitToLast(pageSize)
        } else {
            // Load older messages (before the last timestamp)
            messagesRef
                .child("Sender: $currentUserId")
                .child("ChannelID: $channelId")
                .child("Messages")
                .orderByChild("date")
                .endBefore(lastTimestamp.toDouble())
                .limitToLast(pageSize)
        }

        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<TextMessage>()
                for (messageSnapshot in snapshot.children) {
                    messageSnapshot.getValue(TextMessage::class.java)?.let { message ->
                        message.id = messageSnapshot.key ?: ""
                        messages.add(message)
                    }
                }
                // Sort by date ascending for display
                messages.sortBy { it.date.time }
                continuation.resume(messages)
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
            .startAfter(fromTimestamp.toDouble())

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

    /**
     * Legacy listener method for compatibility
     */
    fun listenForNewMessages(
        currentUserId: String,
        recipientId: String,
        fromTimestamp: Long = 0,
        onNewMessage: (TextMessage) -> Unit
    ): ChildEventListener {
        val channelId = getChatChannelId(currentUserId, recipientId)

        val query = messagesRef
            .child("Sender: $currentUserId")
            .child("ChannelID: $channelId")
            .child("Messages")
            .orderByChild("date")
            .startAfter(fromTimestamp.toDouble())

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(TextMessage::class.java)?.let { message ->
                    message.id = snapshot.key ?: ""
                    onNewMessage(message)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled: ${error.message}")
            }
        }

        query.addChildEventListener(listener)
        return listener
    }

    /**
     * Remove message listener
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
     * Update or create chat channel metadata
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

        val senderChannelData = hashMapOf(
            "recipientId" to recipientId,
            "recipientName" to recipientName,
            "profileImg" to recipientProfileImg,
            "lastMessage" to lastMessage,
            "lastMessageSenderId" to senderId,
            "lastMessageRead" to false,
            "date" to Date(timestamp),
            "isOnline" to false
        )

        val recipientChannelData = hashMapOf(
            "recipientId" to senderId,
            "recipientName" to senderName,
            "profileImg" to senderProfileImg,
            "lastMessage" to lastMessage,
            "lastMessageSenderId" to senderId,
            "lastMessageRead" to false,
            "date" to Date(timestamp),
            "isOnline" to false
        )

        firestore
            .collection("Users")
            .document(senderId)
            .collection("chat channel")
            .document(channelId)
            .set(senderChannelData, SetOptions.merge())

        firestore
            .collection("Users")
            .document(recipientId)
            .collection("chat channel")
            .document(channelId)
            .set(recipientChannelData, SetOptions.merge())
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
        val channelData = hashMapOf(
            "recipientId" to otherUserId,
            "recipientName" to otherUserName,
            "lastMessage" to lastMessage,
            "lastMessageSenderId" to lastMessageSenderId,
            "lastMessageRead" to false,
            "date" to Date(timestamp)
        )

        firestore
            .collection("Users")
            .document(ownerUserId)
            .collection("chat channel")
            .document(channelId)
            .set(channelData, SetOptions.merge())
    }
}
