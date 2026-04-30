package com.mina.moonchat.viewmodels

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.models.ChatItem
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
    private var chatListListener: ListenerRegistration? = null
    private var presenceListener: ValueEventListener? = null
    private var latestChatDocuments: List<com.google.firebase.firestore.DocumentSnapshot> = emptyList()
    private var userOnlineStates: Map<String, Boolean> = emptyMap()

    private val _chatItems = MutableStateFlow<List<ChatItem>>(emptyList())
    val chatItems: StateFlow<List<ChatItem>> = _chatItems.asStateFlow()

    private val _selectedChatUser = MutableLiveData<User?>()
    val selectedChatUser: LiveData<User?> = _selectedChatUser

    init {
        observeUsersPresence()
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

    private fun publishChats() {
        val chats = latestChatDocuments.mapNotNull { document ->
            val recipientId = document.getString("recipientId") ?: return@mapNotNull null
            val timestamp = document.getTimestamp("date")?.toDate()
            val lastMessageRead = document.getBoolean("lastMessageRead") ?: true

            ChatItem(
                chatId = document.id,
                recipientId = recipientId,
                username = document.getString("recipientName") ?: "Unknown",
                profileImg = document.getString("profileImg"),
                onlineState = userOnlineStates[recipientId] ?: false,
                time = timestamp?.let(::formatChatTime).orEmpty(),
                lastMessage = document.getString("lastMessage") ?: "",
                hasUnreadIncoming = !lastMessageRead
            )
        }

        _chatItems.value = chats
    }

    private fun formatChatTime(date: Date): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
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
        super.onCleared()
    }
}
