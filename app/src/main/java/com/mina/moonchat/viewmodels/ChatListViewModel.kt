package com.mina.moonchat.viewmodels

import ChatPagingSource
import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.firestore.FirebaseFirestore
import androidx.paging.PagingData
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

class ChatListViewModel(app: Application): BaseViewModel(
    app,
    (app as MoonChat).repository,
    (app as MoonChat).repo
) {

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var chatListListener: ListenerRegistration? = null
    private var usersListener: ListenerRegistration? = null
    private var latestChatDocuments: List<com.google.firebase.firestore.DocumentSnapshot> = emptyList()
    private var userOnlineStates: Map<String, Boolean> = emptyMap()

    private val _chatFlow = MutableStateFlow<PagingData<ChatItem>>(PagingData.empty())
    val chatFlow: StateFlow<PagingData<ChatItem>> = _chatFlow.asStateFlow()
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
                if (error != null) {
                    return@addSnapshotListener
                }
                latestChatDocuments = snapshot?.documents ?: emptyList()
                publishChats()
            }
    }

    private fun observeUsersPresence() {
        usersListener?.remove()
        usersListener = firestore.collection("Users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    return@addSnapshotListener
                }

                userOnlineStates = snapshot?.documents
                    ?.associate { document ->
                        val isOnline = document.getString("onlineState")
                            ?.equals("Online", ignoreCase = true) == true
                        document.id to isOnline
                    }
                    ?: emptyMap()

                publishChats()
            }
    }

    private fun publishChats() {
        val chats = latestChatDocuments.mapNotNull { document ->
            val recipientId = document.getString("recipientId") ?: return@mapNotNull null
            val timestamp = document.getTimestamp("date")?.toDate()
            ChatItem(
                chatId = document.id,
                recipientId = recipientId,
                username = document.getString("recipientName") ?: "Unknown",
                profileImg = document.getString("profileImg"),
                onlineState = userOnlineStates[recipientId] ?: false,
                time = timestamp?.let(::formatChatTime).orEmpty(),
                lastMessage = document.getString("lastMessage") ?: ""
            )
        }

        _chatFlow.value = PagingData.from(chats)
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
        usersListener?.remove()
        super.onCleared()
    }
}
