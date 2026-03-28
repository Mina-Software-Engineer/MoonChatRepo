package com.mina.moonchat.data.server

import android.content.ContentValues.TAG
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mina.moonchat.models.ChatItem
import com.mina.moonchat.models.TextMessage
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ServerSide1 {

    private val dbReference: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private val realtimeDatabase = FirebaseDatabase.getInstance()
    private val myRef = realtimeDatabase.getReference("Message")

    /* private val dbReference by lazy {
        Firebase.database.reference
    }*/

    private val reference by lazy {
        FirebaseDatabase.getInstance().getReference("Users Messages")
    }

    private val _newMessage = MutableLiveData<TextMessage>()
    val newMessage: LiveData<TextMessage> get() = _newMessage


    private fun getChatChannelId(senderId: String, recipientId: String): String =
        senderId + recipientId

    /** Create send message function*/
    fun sendMessage(message: TextMessage, senderId: String, recipientId: String) {

        val currentMessageSenderRef: DocumentReference
        //creating a path to users/uid
                = dbReference.document(
            "Users Messages/$senderId/${
                getChatChannelId(
                    senderId,
                    recipientId
                )
            }/Messages"
        )

        currentMessageSenderRef.set(message)

        val currentMessageRecipientRef: DocumentReference
        //creating a path to users/uid
                = dbReference.document(
            "Users Messages/$recipientId/${
                getChatChannelId(
                    recipientId,
                    senderId
                )
            }/Messages"
        )

        currentMessageRecipientRef.set(message)

        myRef                               //123
            .child("Sender: $senderId")                 //123456
            .child("ChannelID: ${getChatChannelId(senderId, recipientId)}")
            .child("Messages")
            .push()
            .setValue(message)

        myRef                                   //456
            .child("Sender: $recipientId")           ///456123
            .child("ChannelID: ${getChatChannelId(recipientId, senderId)}")
            .child("Messages")
            .push()
            .setValue(message)
    }

    fun getMessagesFromFirestore(
        senderId: String,
        recipientId: String,
        onComplete: (List<TextMessage>) -> Unit
    ) {
        val messagesList = mutableListOf<TextMessage>()
        val messagesRef = dbReference.collection("Users Messages")
            .document(senderId)
            .collection("${getChatChannelId(senderId, recipientId)}")
            .document("Messages")

        messagesRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w(TAG, "Listen failed.", e)
                onComplete(emptyList())
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val message = snapshot.toObject(TextMessage::class.java)
                message?.let {
                    messagesList.add(it)
                }
                onComplete(messagesList)
            } else {
                Log.d(TAG, "Current data: null")
                onComplete(emptyList())
            }
        }
    }

    fun getLastMessage(senderId: String, recipientId: String) {
        /** For Sender */
        myRef
            .child("Sender: $senderId")
            .child("ChannelID: ${getChatChannelId(senderId, recipientId)}")
            .child("Messages")
            .limitToLast(1)
            .addChildEventListener(object : ChildEventListener {

                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    // This method is called once with the initial value and again
                    // whenever data at this location is updated.
                    val value = snapshot.getValue(TextMessage::class.java)
                    value?.let {
                        _newMessage.value = it
                    }
                    Log.d("cjask", "Value is from sender: ${_newMessage.value}")
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {

                }

                override fun onChildRemoved(snapshot: DataSnapshot) {

                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {

                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Failed to read value.", error.toException())
                }
            })


        /** For Recipient */
        myRef
            .child("Sender: $recipientId")
            .child("ChannelID: ${getChatChannelId(recipientId, senderId)}")
            .child("Messages")
            .limitToLast(1)
            .addChildEventListener(object : ChildEventListener {

                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    // This method is called once with the initial value and again
                    // whenever data at this location is updated.
                    val value = snapshot.getValue(TextMessage::class.java)
                    value?.let {
                        _newMessage.value = it
                    }

                    Log.d("cjask", "Value is from recipient: $value")
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {

                }

                override fun onChildRemoved(snapshot: DataSnapshot) {

                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {

                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Failed to read value.", error.toException())
                }
            })
    }


    fun getMessagesFromRealtimeDatabase(
        senderId: String,
        recipientId: String,
        onComplete: (List<TextMessage>) -> Unit,
        onNewMessage: (TextMessage) -> Unit
    ) {
        val messagesList = mutableListOf<TextMessage>()

        val senderPath = myRef
            .child("Sender: $senderId")
            .child("ChannelID: ${getChatChannelId(senderId, recipientId)}")
            .child("Messages")

        val recipientPath = myRef
            .child("Sender: $recipientId")
            .child("ChannelID: ${getChatChannelId(recipientId, senderId)}")
            .child("Messages")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (messageSnapshot in snapshot.children) {
                    val message = messageSnapshot.getValue(TextMessage::class.java)
                    message?.let {
                        messagesList.add(it)
                    }
                }
                Log.d("checkk", "observer msgs: $messagesList")
                onComplete(messagesList)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Failed to read messages.", error.toException())
                onComplete(emptyList())
            }
        }

        val childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.getValue(TextMessage::class.java)
                message?.let {
                    onNewMessage(it)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Failed to read messages.", error.toException())
            }
        }

        senderPath.addValueEventListener(listener)
        recipientPath.addChildEventListener(childListener)
    }

    suspend fun getMessagesByPage(
        senderId: String,
        recipientId: String,
        lastMessageKey: String?, // Firebase keys are usually strings
        pageSize: Int
    ): List<TextMessage> = suspendCoroutine { continuation ->
        val messagesList = mutableListOf<TextMessage>()

        val recipientPath = myRef
            .child("Sender: $recipientId")
            .child("ChannelID: ${getChatChannelId(recipientId, senderId)}")
            .child("Messages")


        // Construct query for fetching messages
        val query = if (lastMessageKey == null) {
            // Fetch the last `pageSize` messages (initial page)
            recipientPath.orderByKey().limitToLast(pageSize)
        } else {
            // Fetch `pageSize` messages before the lastMessageKey
            recipientPath.orderByKey().endBefore(lastMessageKey).limitToLast(pageSize)

        }

        // Attach listener to fetch messages
        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (messageSnapshot in snapshot.children) {
                    val message = messageSnapshot.getValue(TextMessage::class.java)
                    val messageId = messageSnapshot.key // Retrieve Firebase unique ID
                    message?.let {
                        it.id = messageId ?: "0"
                        messagesList.add(it)
                    }
                }
                continuation.resume(messagesList)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Error fetching messages: ${error.message}")
                continuation.resumeWith(Result.failure(error.toException()))
            }
        })
    }




    fun listenForNewMessages(
        senderId: String,
        recipientId: String,
        lastLoadedMessageId: String?, // ID of the last message fetched by PagingSource
        onNewMessage: (TextMessage) -> Unit
    ) {
        val recipientPath = myRef
            .child("Sender: $recipientId")
            .child("ChannelID: ${getChatChannelId(recipientId, senderId)}")
            .child("Messages")

        val query = if (lastLoadedMessageId != null) {
            recipientPath.orderByKey().startAfter(lastLoadedMessageId)
        } else {
            recipientPath.orderByKey()
        }

        query.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.getValue(TextMessage::class.java)
                val messageId = snapshot.key ?: return

                message?.let {
                    it.id = messageId
                    onNewMessage(it)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("RealTimeListener", "Failed to listen for new messages: ${error.message}")
            }
        })
    }


    fun getChatsListFromServer(userId: String, callback: (List<ChatItem>) -> Unit) {
        val db = FirebaseFirestore.getInstance()
        val chatsCollection = db.collection("Users").document(userId).collection("chat channel")

        chatsCollection.orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    println("Error fetching chats: ${error.message}")
                    callback(emptyList()) // Return an empty list in case of an error
                    return@addSnapshotListener
                }

                val chatList = snapshots?.documents?.mapNotNull { document ->
                    ChatItem(
                        chatId = document.id,
                        username = document.getString("recipientName") ?: "Unknown",
                        lastMessage = document.getString("lastMessage") ?: "",
                        time = document.getTimestamp("date")?.toDate()?.time.toString(),
                        onlineState = document.getBoolean("isOnline") ?: false,
                        profileImg = document.getString("profileImg") ?: ""
                    )
                } ?: emptyList()

                callback(chatList) // Pass the result to the callback
            }
    }
}