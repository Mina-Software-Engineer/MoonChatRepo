package com.mina.moonchat.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.data.server.UserLocalRepository
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date

class ChatViewModel(app: Application) : BaseViewModel(
    app,
    (app as MoonChat).repository,
    (app as MoonChat).repo
) {
    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val repository: UserLocalRepository = (app as MoonChat).repo
    private val presenceRef: DatabaseReference by lazy {
        FirebaseDatabase.getInstance().getReference("Users states")
    }
    private var recipientPresenceListener: ValueEventListener? = null
    private var recipientTypingListener: ValueEventListener? = null
    private var lastTypingResetJob: Job? = null

    // UI State
    val textMessage = MutableLiveData<String>()
    val username = MutableLiveData<String>()
    val onlineStatus = MutableLiveData<String>()
    val profilePicture = MutableLiveData<String>()
    
    private val _recipientTyping = MutableLiveData<Boolean>(false)
    val recipientTyping: LiveData<Boolean> = _recipientTyping

    // Error state
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Loading state
    private val _isSending = MutableLiveData<Boolean>()
    val isSending: LiveData<Boolean> = _isSending

    // Track current chat for cleanup
    private var currentRecipientId: String? = null

    /**
     * Get message stream for a chat
     * This combines Room + Firebase pagination
     */
    fun getMessageStream(recipientId: String): Flow<PagingData<TextMessage>> {
        currentRecipientId = recipientId
        val currentUserId = mAuth.currentUser?.uid ?: throw IllegalStateException("User not logged in")

        // Start real-time sync
        repository.activateRealtimeSync(currentUserId, recipientId)

        return repository.getChatStream(currentUserId, recipientId)
            .cachedIn(viewModelScope)
    }

    /**
     * Stop real-time sync when leaving chat
     */
    fun stopSync() {
        currentRecipientId?.let { recipientId ->
            mAuth.currentUser?.uid?.let { currentUserId ->
                repository.stopRealtimeSync(currentUserId, recipientId)
                lastTypingResetJob?.cancel()
                FirebaseDatabase.getInstance().getReference("typingStatus")
                    .child(recipientId)
                    .child(currentUserId)
                    .setValue(false)
            }
        }
        stopObservingRecipientPresence()
        stopObservingRecipientTyping()
    }

    fun observeRecipientPresence(recipientId: String) {
        stopObservingRecipientPresence()
        observeRecipientTyping(recipientId)

        recipientPresenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val state = snapshot.child("state").getValue(String::class.java)
                    ?: snapshot.getValue(String::class.java)
                    ?: "Offline"
                onlineStatus.postValue(state)
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                Log.e(TAG, "Failed to observe recipient presence: ${error.message}")
            }
        }

        presenceRef.child(recipientId).addValueEventListener(recipientPresenceListener!!)
    }

    private fun stopObservingRecipientPresence() {
        val recipientId = currentRecipientId ?: return
        val listener = recipientPresenceListener ?: return
        presenceRef.child(recipientId).removeEventListener(listener)
        recipientPresenceListener = null
    }

    fun observeRecipientTyping(recipientId: String) {
        stopObservingRecipientTyping()
        val currentUserId = mAuth.currentUser?.uid ?: return
        val typingRef = FirebaseDatabase.getInstance().getReference("typingStatus").child(currentUserId).child(recipientId)

        recipientTypingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val isTyping = snapshot.getValue(Boolean::class.java) ?: false
                _recipientTyping.postValue(isTyping)
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                Log.e(TAG, "Failed to observe recipient typing: ${error.message}")
            }
        }
        typingRef.addValueEventListener(recipientTypingListener!!)
    }

    private fun stopObservingRecipientTyping() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val recipientId = currentRecipientId ?: return
        val listener = recipientTypingListener ?: return
        FirebaseDatabase.getInstance().getReference("typingStatus").child(currentUserId).child(recipientId).removeEventListener(listener)
        recipientTypingListener = null
    }

    fun onTextChanged(text: String) {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val recipientId = currentRecipientId ?: return
        val ref = FirebaseDatabase.getInstance().getReference("typingStatus").child(recipientId).child(currentUserId)

        if (text.isEmpty()) {
            lastTypingResetJob?.cancel()
            ref.setValue(false)
        } else {
            lastTypingResetJob?.cancel()
            ref.setValue(true)
            lastTypingResetJob = viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                ref.setValue(false)
            }
        }
    }

    /**
     * Handle send button click
     */
    fun onSendClicked(recipientUser: User, messageText: String) {
        if (messageText.isBlank()) return

        val senderId = mAuth.currentUser?.uid ?: run {
            _errorMessage.value = "User not logged in"
            return
        }

        _isSending.value = true

        getCurrentUserInfo { currentUser ->
            val newMessage = TextMessage(
                id = "", // Will be generated by Firebase
                text = messageText.trim(),
                senderId = senderId,
                recipientId = recipientUser.userId,
                senderName = currentUser.displayName ?: "",
                recipientName = recipientUser.displayName ?: "",
                date = Date(),
                channelId = repository.getChannelId(senderId, recipientUser.userId),
                status = "PENDING",
                isRead = false
            )

            viewModelScope.launch {
                try {
                    repository.sendMessage(
                        newMessage,
                        currentUser.profileImg,
                        recipientUser.profileImg
                    )
                    Log.d("TAG", "Message sent successfully")
                    textMessage.value = "" // Clear input
                } catch (e: Exception) {
                    Log.e("TAG", "Failed to send message: ${e.message}")
                    _errorMessage.value = "Failed to send message"
                } finally {
                    _isSending.value = false
                }
            }
        }
    }

    /**
     * Retry sending a failed message
     */
    fun retryMessage(message: TextMessage) {
        viewModelScope.launch {
            try {
                repository.retryMessage(message)
                Log.d(TAG, "Message retry initiated")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to retry message: ${e.message}")
                _errorMessage.value = "Failed to retry message"
            }
        }
    }

    /**
     * Mark messages as read
     */
    fun markMessagesAsRead(recipientId: String) {
        viewModelScope.launch {
            try {
                val currentUserId = mAuth.currentUser?.uid ?: return@launch
                val channelId = repository.getChannelId(currentUserId, recipientId)
                repository.markMessagesAsRead(channelId, currentUserId, recipientId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark messages as read: ${e.message}")
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Sync pending messages on startup
     */
    fun syncPendingMessages() {
        viewModelScope.launch {
            try {
                repository.syncPendingMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync pending messages: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSync()
    }
}
