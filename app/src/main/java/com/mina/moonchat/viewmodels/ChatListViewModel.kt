package com.mina.moonchat.viewmodels

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.models.ChatItem
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class ChatListViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repository,
    (app as MoonChat).repo
) {

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val presenceRef by lazy { FirebaseDatabase.getInstance().getReference("Users states") }
    private val messagesRootRef by lazy { FirebaseDatabase.getInstance().getReference("Message") }
    private var chatListListener: ListenerRegistration? = null
    private var presenceListener: ValueEventListener? = null
    private var typingStatusListener: ValueEventListener? = null
    private var lastMessageListener: ValueEventListener? = null
    private var latestChatDocuments: List<com.google.firebase.firestore.DocumentSnapshot> = emptyList()
    private var userOnlineStates: Map<String, Boolean> = emptyMap()
    private var typingUsersMap: Map<String, Boolean> = emptyMap()
    private var latestMessagesByChannel: Map<String, LastMessagePreview> = emptyMap()
    private var unreadCountsByChannel: Map<String, Int> = emptyMap()

    private val _chatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    val chatItems: StateFlow<List<ChatItem>> = _chatItems.asStateFlow()

    private val _selectedChatUser = MutableLiveData<User?>()
    val selectedChatUser: LiveData<User?> = _selectedChatUser

    init {
        observeUsersPresence()
        observeTypingStatuses()
        observeLastMessages()
        fetchChats()
    }

    private fun fetchChats() {
        val currentUserId = mAuth.currentUser?.uid ?: return

        chatListListener?.remove()
        chatListListener = firestore.collection("Users")
            .document(currentUserId)
            .collection("chat channel")
            .orderBy("date", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                latestChatDocuments = snapshot?.documents ?: emptyList()
                publishChats()
            }
    }

    private fun observeUsersPresence() {
        presenceListener?.let { presenceRef.removeEventListener(it) }
        presenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                userOnlineStates = snapshot.children.associate { child ->
                    val isOnline = child.child("state").getValue(String::class.java)
                        ?.equals("Online", ignoreCase = true) == true
                    (child.key ?: "") to isOnline
                }
                publishChats()
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) = Unit
        }

        presenceRef.addValueEventListener(presenceListener!!)
    }

    private fun observeTypingStatuses() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val typingRef = FirebaseDatabase.getInstance().getReference("typingStatus").child(currentUserId)

        typingStatusListener?.let { typingRef.removeEventListener(it) }
        typingStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                typingUsersMap = snapshot.children.associate { child ->
                    (child.key ?: "") to (child.getValue(Boolean::class.java) ?: false)
                }
                publishChats()
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) = Unit
        }
        typingRef.addValueEventListener(typingStatusListener!!)
    }

    private fun observeLastMessages() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val userMessagesRef = messagesRootRef.child("Sender: $currentUserId")

        lastMessageListener?.let { userMessagesRef.removeEventListener(it) }
        lastMessageListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tempLastMessages = mutableMapOf<String, LastMessagePreview>()
                val tempUnreadCounts = mutableMapOf<String, Int>()

                for (channelSnapshot in snapshot.children) {
                    val channelId = channelSnapshot.key
                        ?.removePrefix("ChannelID: ")
                        ?.takeIf { it.isNotBlank() }
                        ?: continue

                    val messagesSnapshot = channelSnapshot.child("Messages")
                    var unreadCount = 0
                    var latestMessage: TextMessage? = null

                    for (messageSnapshot in messagesSnapshot.children) {
                        val msg = messageSnapshot.getValue(TextMessage::class.java) ?: continue
                        msg.id = messageSnapshot.key ?: ""
                        
                        val msgIsRead = messageSnapshot.child("isRead").getValue(Boolean::class.java) ?: msg.isRead

                        // Count unread messages that came from the other person
                        if (msg.senderId != currentUserId && !msgIsRead) {
                            unreadCount++
                        }

                        if (latestMessage == null || msg.date.time > latestMessage.date.time) {
                            latestMessage = msg.copy(isRead = msgIsRead)
                        }
                    }

                    if (latestMessage != null) {
                        tempLastMessages[channelId] = LastMessagePreview(
                            text = latestMessage.text,
                            timestamp = latestMessage.date.time,
                            status = latestMessage.deliveryStatusText()
                        )
                    }
                    tempUnreadCounts[channelId] = unreadCount
                }

                latestMessagesByChannel = tempLastMessages
                unreadCountsByChannel = tempUnreadCounts
                publishChats()
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) = Unit
        }

        userMessagesRef.addValueEventListener(lastMessageListener!!)
    }

    private fun publishChats() {
        val chats = latestChatDocuments.mapNotNull { document ->
            val recipientId = document.getString("recipientId") ?: return@mapNotNull null
            val firestoreTimestamp = document.getTimestamp("date")?.toDate()
            val latestMessage = latestMessagesByChannel[document.id]
            val effectiveTimestamp = latestMessage?.timestamp ?: firestoreTimestamp?.time ?: 0L

            val currentUserId = mAuth.currentUser?.uid
            val calculatedUnreadCount = unreadCountsByChannel[document.id] ?: 0
            val isUnreadCalculated = calculatedUnreadCount > 0

            val lastMessageSenderId = document.getString("lastMessageSenderId")
            val lastMessageStatus = when {
                latestMessage != null -> latestMessage.status
                lastMessageSenderId == currentUserId -> "Sent"
                else -> ""
            }

            ChatItem(
                chatId = document.id,
                recipientId = recipientId,
                username = document.getString("recipientName") ?: "Unknown",
                profileImg = document.getString("profileImg"),
                onlineState = userOnlineStates[recipientId] ?: false,
                time = effectiveTimestamp.takeIf { it > 0L }?.let { formatChatTime(Date(it)) }.orEmpty(),
                lastMessage = latestMessage?.text ?: document.getString("lastMessage") ?: "",
                hasUnreadIncoming = isUnreadCalculated,
                unreadCount = calculatedUnreadCount,
                isTyping = typingUsersMap[recipientId] ?: false,
                lastMessageStatus = lastMessageStatus
            )
        }
            .sortedByDescending { chat ->
                latestMessagesByChannel[chat.chatId]?.timestamp
                    ?: latestChatDocuments.firstOrNull { it.id == chat.chatId }?.getTimestamp("date")?.toDate()?.time
                    ?: 0L
            }

        _chatItems.value = chats
    }

    private fun formatChatTime(date: Date): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    }

    fun openChat(recipientId: String) {
        firestore.collection("Users")
            .document(recipientId)
            .get()
            .addOnSuccessListener { document ->
                _selectedChatUser.value = document.toObject(User::class.java)
            }
    }

    fun onChatNavigated() {
        _selectedChatUser.value = null
    }

    override fun onCleared() {
        chatListListener?.remove()
        presenceListener?.let { presenceRef.removeEventListener(it) }
        mAuth.currentUser?.uid?.let { currentUserId ->
            typingStatusListener?.let {
                FirebaseDatabase.getInstance().getReference("typingStatus").child(currentUserId).removeEventListener(it)
            }
            lastMessageListener?.let {
                messagesRootRef.child("Sender: $currentUserId").removeEventListener(it)
            }
        }
        super.onCleared()
    }

    private data class LastMessagePreview(
        val text: String,
        val timestamp: Long,
        val status: String = ""
    )

    private inline fun <T, R : Any> Iterable<T>.associateNotNull(transform: (T) -> Pair<String, R>?): Map<String, R> {
        val destination = mutableMapOf<String, R>()
        for (element in this) {
            val pair = transform(element) ?: continue
            destination[pair.first] = pair.second
        }
        return destination
    }
}
