package com.mina.moonchat.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.R
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.models.ChatItem
import com.mina.moonchat.models.ChatListLoadState
import com.mina.moonchat.models.UnreadMessageRecord
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.models.User
import com.mina.moonchat.models.countUnreadIncomingMessages
import com.mina.moonchat.models.filterChatsByPartnerName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatListViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repo
) {

    private val appContext = app.applicationContext
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val presenceRef by lazy { FirebaseDatabase.getInstance().getReference("Users states") }
    private val messagesRootRef by lazy { FirebaseDatabase.getInstance().getReference("Message") }

    private var chatListListener: ListenerRegistration? = null

    private var observedPresenceRef: DatabaseReference? = null
    private var presenceListener: ValueEventListener? = null

    private var observedTypingRef: DatabaseReference? = null
    private var typingStatusListener: ValueEventListener? = null

    private var observedLastMessagesRef: Query? = null
    private var lastMessageListener: ValueEventListener? = null

    private var latestChatDocuments: List<com.google.firebase.firestore.DocumentSnapshot> = emptyList()
    private var hasChatSnapshot = false
    private var userOnlineStates: Map<String, Boolean> = emptyMap()
    private var typingUsersMap: Map<String, Boolean> = emptyMap()
    private var latestMessagesByChannel: Map<String, LastMessagePreview> = emptyMap()
    private var unreadCountsByChannel: Map<String, Int> = emptyMap()

    private val allChatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _loadState = MutableStateFlow(ChatListLoadState.LOADING)
    val loadState: StateFlow<ChatListLoadState> = _loadState.asStateFlow()
    val uiState = combine(allChatItems, _searchQuery, _loadState) { all, query, status ->
        ChatListUiState(
            query = query,
            conversations = filterChatsByPartnerName(all, query),
            allConversationCount = all.size,
            loadState = status
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    private val _selectedChatUser = MutableLiveData<User?>()
    val selectedChatUser: LiveData<User?> = _selectedChatUser

    init {
        observeUsersPresence()
        observeTypingStatuses()
        observeLastMessages()
        fetchChats()
    }

    private fun fetchChats() {
        val currentUserId = mAuth.currentUser?.uid ?: run {
            _loadState.value = ChatListLoadState.ERROR
            return
        }

        if (!hasChatSnapshot) _loadState.value = ChatListLoadState.LOADING
        chatListListener?.remove()
        chatListListener = firestore.collection("Users")
            .document(currentUserId)
            .collection("chat channel")
            .orderBy("date", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    _loadState.value = if (
                        latestChatDocuments.isNotEmpty() &&
                        error is FirebaseFirestoreException &&
                        error.code == FirebaseFirestoreException.Code.UNAVAILABLE
                    ) ChatListLoadState.OFFLINE_WITH_CACHE else ChatListLoadState.ERROR
                    return@addSnapshotListener
                }
                if (snapshot == null) {
                    _loadState.value = ChatListLoadState.ERROR
                    return@addSnapshotListener
                }
                latestChatDocuments = snapshot?.documents ?: emptyList()
                hasChatSnapshot = true
                _loadState.value = if (snapshot.metadata.isFromCache) {
                    ChatListLoadState.OFFLINE_WITH_CACHE
                } else {
                    ChatListLoadState.READY
                }
                publishChats()
            }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun retryLoadingChats() {
        fetchChats()
    }

    private fun observeUsersPresence() {
        stopObservingUsersPresence()
        observedPresenceRef = presenceRef

        presenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
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

    private fun stopObservingUsersPresence() {
        presenceListener?.let { listener ->
            observedPresenceRef?.removeEventListener(listener)
        }
        presenceListener = null
        observedPresenceRef = null
    }

    private fun observeTypingStatuses() {
        stopObservingTypingStatuses()

        val currentUserId = mAuth.currentUser?.uid ?: return
        val typingRef = FirebaseDatabase.getInstance().getReference("typingStatus").child(currentUserId)
        observedTypingRef = typingRef

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

    private fun stopObservingTypingStatuses() {
        typingStatusListener?.let { listener ->
            observedTypingRef?.removeEventListener(listener)
        }
        typingStatusListener = null
        observedTypingRef = null
    }

    private fun observeLastMessages() {
        stopObservingLastMessages()

        val currentUserId = mAuth.currentUser?.uid ?: return
        val userMessagesRef = messagesRootRef.child("Sender: $currentUserId")
        observedLastMessagesRef = userMessagesRef

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
                    val unreadRecords = mutableListOf<UnreadMessageRecord>()
                    var latestMessage: TextMessage? = null

                    for (messageSnapshot in messagesSnapshot.children) {
                        val msg = messageSnapshot.getValue(TextMessage::class.java) ?: continue
                        msg.id = messageSnapshot.key ?: ""

                        val msgIsRead = messageSnapshot.child("isRead").getValue(Boolean::class.java) ?: msg.isRead
                        val msgIsDelivered = messageSnapshot.child("isDelivered").getValue(Boolean::class.java) ?: msg.isDelivered

                        val fullMsg = msg.copy(isRead = msgIsRead, isDelivered = msgIsDelivered)

                        val messageId = messageSnapshot.key.orEmpty()
                        if (messageId.isNotBlank()) {
                            unreadRecords += UnreadMessageRecord(messageId, fullMsg.senderId, fullMsg.isRead)
                        }

                        if (latestMessage == null || fullMsg.date.time > latestMessage.date.time) {
                            latestMessage = fullMsg
                        }
                    }

                    if (latestMessage != null) {
                        tempLastMessages[channelId] = LastMessagePreview(
                            text = latestMessage.text,
                            timestamp = latestMessage.date.time,
                            status = latestMessage.deliveryStatusText(),
                            senderId = latestMessage.senderId,
                            type = latestMessage.type
                        )
                    }
                    // Canonical unread path: count unique incoming message IDs in the current user's RTDB mirror.
                    // Listener callbacks never increment a counter, so global/active listener echoes cannot inflate it.
                    tempUnreadCounts[channelId] = countUnreadIncomingMessages(unreadRecords, currentUserId)
                }

                latestMessagesByChannel = tempLastMessages
                unreadCountsByChannel = tempUnreadCounts
                publishChats()
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) = Unit
        }

        userMessagesRef.addValueEventListener(lastMessageListener!!)
    }

    private fun stopObservingLastMessages() {
        lastMessageListener?.let { listener ->
            observedLastMessagesRef?.removeEventListener(listener)
        }
        lastMessageListener = null
        observedLastMessagesRef = null
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

            val lastMessageSenderId = latestMessage?.senderId ?: document.getString("lastMessageSenderId")
            val lastMessageStatus = when {
                latestMessage != null && latestMessage.senderId == currentUserId -> latestMessage.status
                latestMessage == null && lastMessageSenderId == currentUserId -> "Sent"
                else -> ""
            }

            val preview = when {
                latestMessage?.type == com.mina.moonchat.models.MessageType.IMAGE ->
                    appContext.getString(R.string.chat_preview_photo)
                !latestMessage?.text.isNullOrBlank() -> latestMessage?.text.orEmpty()
                else -> document.getString("lastMessage") ?: ""
            }

            ChatItem(
                chatId = document.id,
                recipientId = recipientId,
                username = document.getString("recipientName") ?: "Unknown",
                profileImg = document.getString("profileImg"),
                onlineState = userOnlineStates[recipientId] ?: false,
                time = effectiveTimestamp.takeIf { it > 0L }?.let { formatChatTime(Date(it)) }.orEmpty(),
                lastMessage = preview,
                hasUnreadIncoming = isUnreadCalculated,
                unreadCount = calculatedUnreadCount,
                isTyping = typingUsersMap[recipientId] ?: false,
                lastMessageStatus = lastMessageStatus
            ) to effectiveTimestamp
        }
            .sortedByDescending { it.second }
            .map { it.first }

        allChatItems.value = chats
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
        chatListListener = null

        stopObservingUsersPresence()
        stopObservingTypingStatuses()
        stopObservingLastMessages()

        super.onCleared()
    }

    private data class LastMessagePreview(
        val text: String,
        val timestamp: Long,
        val status: String = "",
        val senderId: String = "",
        val type: String = com.mina.moonchat.models.MessageType.TEXT
    )
}

data class ChatListUiState(
    val query: String = "",
    val conversations: List<ChatItem> = emptyList(),
    val allConversationCount: Int = 0,
    val loadState: ChatListLoadState = ChatListLoadState.LOADING
)
