package com.mina.moonchat.viewmodels

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.base.BaseViewModel
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
class ChatViewModel1(app: Application) : BaseViewModel(app, (app as MoonChat).repository) {


    //a reference to firebase storage
    private val storageInstance: FirebaseStorage by lazy {
        FirebaseStorage.getInstance()
    }

    //creating an initial image path in storage reference
    private val currentImageRef: StorageReference
        get() = storageInstance.reference

    private val presenceRef: DatabaseReference by lazy {
        FirebaseDatabase.getInstance().getReference("Users states")
    }

    //a reference to firestore database
    private val firestoreInstance: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    //a reference to Users/user id document
    private val mCurrentUserDocRef: DocumentReference
        get() = firestoreInstance.document(
            "Users/${FirebaseAuth.getInstance().currentUser?.uid}"
        )

    //current logged in user reference
    */
/*private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //mAuth = FirebaseAuth.getInstance()
    }*//*


    private val _username = MutableLiveData<String>()
    val username: LiveData<String> = _username

    private val _pfp = MutableLiveData<String?>()
    val pfp: LiveData<String?> = _pfp


    private val _message = MutableLiveData<List<TextMessage>>()
    var message: LiveData<List<TextMessage>> = _message

    */
/*private val _senderItem = MutableLiveData<SenderTextItem>()
    val senderItem: LiveData<SenderTextItem> = _senderItem*//*


    private val _messageList = MutableLiveData<List<TextMessage>>()
    var messageList: LiveData<List<TextMessage>> = _messageList


    private val _msg = MutableLiveData<List<TextMessage>>()
    var msg: LiveData<List<TextMessage>> = _msg

    val newMessage: LiveData<TextMessage> = localRepo.newMessage


    //var pagedMessages: Flow<PagingData<TextMessage>> =


    var textMessage = ""
    private val pageSize = 20  // Number of messages per page
    private var currentPage = 0  // Keeps track of the current page

    fun updateUserInfoWithUI(recipientUser: User) {
        _username.value = recipientUser.displayName
    }


    fun sendMessageToTheServerAndDB(recipientUser: User, messageText: String) {

        viewModelScope.launch {
            getCurrentUserInfo { currentUser ->

                val messageContent = TextMessage(
                    text = messageText,
                    senderId = mAuth.currentUser!!.uid,
                    recipientId = recipientUser.userId,
                    senderName = currentUser.displayName,
                    recipientName = recipientUser.displayName,
                    date = getCurrentFormattedDateAsDate(),
                    type = "TEXT",
                    channelId = currentUser.userId + recipientUser.userId
                )

                */
/** 1. Message to Server as Sender *//*

                localRepo.addNewMessageToServer(
                    messageContent,
                    currentUser.userId,
                    recipientUser.userId
                )

                */
/** 3. Check for New Chat Channel *//*

                val docData = mapOf(
                    "channelId " to currentUser.userId + recipientUser.userId,
                    "date" to getCurrentFormattedDateAsDate(),
                    "recipientId" to recipientUser.userId,
                    "recipientName" to recipientUser.displayName,
                    "senderId" to mAuth.currentUser!!.uid,
                    "senderName" to currentUser.displayName,
                    "text" to messageText,
                    "type" to "TEXT",
                    "pfp" to recipientUser.profileImg!!,
                    "isOnline" to recipientUser.onlineState
                )
                checkAndCreateDocument(recipientUser.userId, docData)


                */
/** 2. Message to DB as Sender *//*

                //getLastMessageFromServer(mAuth.currentUser!!.uid, recipientUser.userId, recipientUser)
                //insertMessageToDB(messageText, recipientUser)
            }

        }

    }

    private fun checkAndCreateDocument(recipientId: String, chatItem: Map<String, Any>) {
        val db = FirebaseFirestore.getInstance() // Get Firestore instance

        val docRef =
            db.collection("Users").document(mAuth.currentUser!!.uid).collection("chat channel")
                .document(recipientId)

        docRef.get()
            .addOnSuccessListener { document ->

                // Create a new document if it does not exist
                docRef.set(chatItem)
                    .addOnSuccessListener {
                        Log.d("sadadfad", "Document created successfully!")
                    }
                    .addOnFailureListener { e ->
                        println("Error creating document: ${e.message}")
                    }

            }
            .addOnFailureListener { e ->
                println("Error checking document: ${e.message}")
            }
    }

    */
/*fun insertNewMessageFromServerToDB(senderID: String, recipientID: String) {
        viewModelScope.launch {
            localRepo.getLastMessageFromServer(senderID, recipientID, recipientID)
        }
    }*//*


    */
/** Observer Function *//*

    fun getLastMessageFromServer(
        senderID: String,
        recipientID: String,
        lastLoadedMessageId: String?,
        onNewMessage: (TextMessage) -> Unit
    ) {
        //Adding the newly sent message to the database if not null
        //getLastMessageFromDB(senderID, recipientID)

        viewModelScope.launch {
            localRepo.getLastMessageInfoFromServer(
                senderID,
                recipientID,
                lastLoadedMessageId,
                onNewMessage
            )
            //Log.d("checkkkk", "Message text from Server is: $message" )
            //insertMessageToDB(message?.text ?: "null", recipientUser)
            //if (newMessage.value != null) {
            //insertMessageToDB(newMessage.value!!.text, recipientUser)
            //messageList = localRepo.getMessagesInfoFromDB(senderID + recipientID)
            //}

            //getLastMessageFromDB(mAuth.currentUser!!.uid, recipientUser.userId)
        }

        //Getting all messages once the user enter the chat screen

    }

    private fun insertMessageToDB(textMsg: String, recipientUser: User) {

        getCurrentUserInfo { currentUser ->
            val messageInfo = TextMessage(
                text = textMsg,
                senderId = currentUser.userId,
                recipientId = recipientUser.userId,
                senderName = currentUser.displayName,
                recipientName = recipientUser.displayName,
                date = getCurrentFormattedDateAsDate(),
                type = "TEXT",
                channelId = currentUser.userId + recipientUser.userId
            )

            viewModelScope.launch {
                localRepo.insertNewMessageToDB(messageInfo)
                //getLastMessageFromDB(currentUser.userId, recipientUser.userId)
            }
        }
    }

    private fun getLastMessageFromDB(senderID: String, recipientID: String) {
        viewModelScope.launch {
            val lastMsg = localRepo.getLastMessageInfoFromDB(senderID + recipientID, recipientID)
            if (lastMsg[0].text.isEmpty()) {
                _msg.value = lastMsg
            }

        }
        //Log.d("checkkkkkk", "last message is: ${lastMsg.value!!.text}")
    }

    fun loadMessages(senderID: String, recipient: User, onNewMessage: (TextMessage) -> Unit) {
        viewModelScope.launch {
            if (checkForNewMessage(senderID, recipient, onNewMessage)) {
                insertMessageToDB(newMessage.value!!.text, recipient)
            }

            val msgList = localRepo.getMessagesInfoFromDB(senderID + recipient.userId)
            if (msgList.isNotEmpty()) {
                _messageList.value = msgList
            }
            */
/* if (msgList.isNotEmpty())
                 _messageList.value = msgList*//*

        }

    }

    private fun checkForNewMessage(
        senderID: String,
        recipient: User,
        onNewMessage: (TextMessage) -> Unit
    ): Boolean {
        //getLastMessageFromServer(senderID, recipient.userId, onNewMessage)
        return newMessage.value != null
    }


    fun createChatChannel(recipientUser: User, onComplete: (channelId: String) -> Unit) {
        firestoreInstance.collection("Users")
            .document(mAuth.currentUser!!.uid)
            .collection("chat channel")
            .document(recipientUser.userId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    onComplete(document["channelId"] as String)
                    //
                    return@addOnSuccessListener
                }

                val newChatChannel = firestoreInstance.collection("Users").document()

                firestoreInstance.collection("Users")
                    .document(recipientUser.userId)
                    .collection("chat channel")
                    .document(mAuth.currentUser!!.uid)
                    .set(mapOf("channelId" to newChatChannel.id))

                firestoreInstance.collection("Users")
                    .document(mAuth.currentUser!!.uid)
                    .collection("chat channel")
                    .document(recipientUser.userId)
                    .set(mapOf("channelId" to newChatChannel.id))

                onComplete(newChatChannel.id)

                //updateDatabaseWithMessages(newChatChannel.id)

            }
    }


    private fun isOnline(): Boolean {
        val connectivityManager =
            app.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connectivityManager != null) {
            val capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_CELLULAR")
                    return true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_WIFI")
                    return true
                }
            }
        }
        return false
    }

    */
/*fun sendMessageInfoToDB(text: String) {
        localRepo.insertNewMessagesToDB()
    }*//*



    */
/*private fun sentMessage(channelID: String, messageSent: Message, recipientUser: String) {
        val messageContent = mutableMapOf<String, Any>()
        messageContent["text"] = message.value!!
        messageContent["senderId"] = messageSent.senderId
        messageContent["recipientId"] = messageSent.recipientId
        messageContent["senderName"] = messageSent.senderName
        messageContent["recipientName"] = messageSent.recipientName
        messageContent["date"] = messageSent.date
        messageContent["type"] = messageSent.type

        chatChannelCollectionRef.document(channelID).collection("messages").add(messageSent)

        firestoreInstance.collection("Users")
            .document(mAuth.currentUser!!.uid)
            .collection("chat channel")
            .document(recipientUser)
            .update(messageContent)

        firestoreInstance.collection("Users")
            .document(recipientUser)
            .collection("chatChannel")
            .document(mAuth.currentUser!!.uid)
            .update(messageContent)
    }*//*


    */
/*fun getMessageInfo(recipientUser: User) {

        createChatChannel(recipientUser) { channelID ->
            val query = chatChannelCollectionRef.document(channelID).collection("messages")
                .orderBy("date", Query.Direction.DESCENDING)

            query.addSnapshotListener { querySnapshot, _ ->
                //Clear Recycler view adapter
                messageList.clear()
                querySnapshot!!.documents.forEach { document ->
                    if (document["type"] == MessageType.TEXT) {
                        val textMessage = document.toObject(TextMessage::class.java)!!
                        if (textMessage.senderId == mAuth.currentUser!!.uid) {
                            messagesender = "sender"
                            messageList.add(
                                TextMessage(
                                    textMessage.text,
                                    textMessage.senderId,
                                    textMessage.recipientId,
                                    textMessage.senderName,
                                    textMessage.recipientName,
                                    textMessage.date,
                                    textMessage.type
                                )
                            )
                            Log.d("check", "The view is: sender")
                            _senderItem.value = SenderTextItem(messageList)
                        } else {
                            messagesender = "receiver"
                            messageList.add(
                                TextMessage(
                                    textMessage.text,
                                    textMessage.senderId,
                                    textMessage.recipientId,
                                    textMessage.senderName,
                                    textMessage.recipientName,
                                    textMessage.date,
                                    textMessage.type
                                )
                            )
                            Log.d("check", "The view is: receiver")
                            _receiverItem.value = ReceiverTextItem(messageList)
                        }
                    } else {
                        val imageMessage = document.toObject(ImageMessage::class.java)!!
                        messagesender = "sender"
                        if (imageMessage.senderId == mAuth.currentUser!!.uid) {
                            messageList.add(
                                ImageMessage(
                                    imageMessage.imagePath,
                                    imageMessage.senderId,
                                    imageMessage.recipientId,
                                    imageMessage.senderName,
                                    imageMessage.recipientName,
                                    imageMessage.date,
                                    imageMessage.type
                                )
                            )
                        } else {
                            messagesender = "receiver"
                            messageList.add(
                                ImageMessage(
                                    imageMessage.imagePath,
                                    imageMessage.senderId,
                                    imageMessage.recipientId,
                                    imageMessage.senderName,
                                    imageMessage.recipientName,
                                    imageMessage.date,
                                    imageMessage.type
                                )
                            )
                        }
                    }
                }
            }
        }
    }*//*


    fun loadAllMessages(
        senderID: String,
        recipient: User,
        onComplete: (List<TextMessage>) -> Unit,
        onNewMessage: (TextMessage) -> Unit
    ): Flow<PagingData<TextMessage>> {
        //viewModelScope.launch(Dispatchers.IO) {
        return localRepo.getPagerMessages(senderID, recipient, onComplete, onNewMessage)
            .cachedIn(viewModelScope)

        //Log.d("checkk", "This is last message from viewModel: " + pagedMessages.value)
        //getLastMessageFromServer(mAuth.currentUser!!.uid, recipient.userId, recipient)

        // }


    }

    private fun getCurrentFormattedDateAsDate(): Date {
        val date: Date = Calendar.getInstance().time
        val formatDateTime = SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault())
        val formattedDateTime = formatDateTime.format(date) // Combine date and time
        return formatDateTime.parse(formattedDateTime)!! // Parse back into a Date object
    }

}*/
