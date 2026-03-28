package com.mina.moonchat.data

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.UserInfoDTO
import com.mina.moonchat.data.local.AuthDao
import com.mina.moonchat.data.local.MessageDao
import com.mina.moonchat.data.local.UserDao
import com.mina.moonchat.data.server.ServerSide
import com.mina.moonchat.intro_activity.data.Result
import com.mina.moonchat.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.*

private const val PAGE_SIZE = 20

class UserLocalRepository1(
    private val currentUserDao: AuthDao,
    private val userDao: UserDao,
    private val messageDao: MessageDao,
    private val server: ServerSide
) : UserDataSource {

    private val firestoreInstance: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val chatChannelCollectionRef = firestoreInstance.collection("chatChannels")
    private val mAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var messageList = arrayListOf<TextMessage>()

    //val newMessage: LiveData<TextMessage> = server.newMessage
    val pagedMessages = MutableLiveData<PagingData<TextMessage>>()


    /*private val _messages = MutableLiveData<List<TextMessage>>()
    val messages: LiveData<List<TextMessage>> = _messages*/

    override suspend fun getUsersInfo(): Result<List<UserInfoDTO>> {
        return try {
            Result.Success(userDao.getChatsList())
        } catch (ex: java.lang.Exception) {
            Result.Error(ex.localizedMessage)
        }
    }

    //Adding current Auth user to database
    override suspend fun insertNewUser(user: UserInfoDTO) {
        userDao.saveChat(user)
    }

    override suspend fun getMessagesInfoFromDB(channelId: String): List<TextMessage> {
     /*   Log.d(
            "checkkkk",
            "Message text from DB is: ${
                messageDao.getAllMessagesInfo(channelId).asTextMessageItem()
            }"
        )*/
        return messageDao.getAllMessagesInfo(channelId, 0, 0).asTextMessageItem()

        /*return Transformations.map(messageDao.getAllMessagesInfo(channelId)){
            Log.d("checkk", "this is msg from DB: " + it[0].messageText)


            it.asTextMessageItem()

        }*/

    }

    override suspend fun getLastMessageInfoFromDB(
        channelId: String,
        recipientID: String
    ): List<TextMessage> {
        //val lastMessage: LiveData<List<MessagesDTO>> = messageDao.getLastMessageInfo(channelId, recipientID)

        val msg = messageDao.getLastMessageInfo(channelId, ", ")
        Log.d("askdhgakujsd", "Failed to read value. $msg")
        return msg.asTextMessageItem()

    }

    fun getLastMessageInfoFromServer(senderID: String, recipientID: String, lastLoadedMessageId: Long, onNewMessage: (TextMessage) -> Unit) {
        server.listenForNewMessages(senderID, recipientID, lastLoadedMessageId, onNewMessage)
    }

    //Getting recipient message from server
    suspend fun getNewMessageFromServerToDB(senderID: String, recipientID: String) {
        //insertNewMessageToDB(server.readMessage(senderID, recipientID))
    }


    //Adding new messages to the Database
    override suspend fun insertNewMessageToDB(message: TextMessage) {
        Log.d("checkk", "this is msg to DB: " + message.text)
        /*messageDao.addCurrentMessageInfo(
            MessagesDTO(
                messageText = message.text,
                senderName = message.senderName,
                recipientName = message.recipientName,
                senderId = message.senderId,
                recipientId = message.recipientId,
                messageTime = 0,
                messageType = message.type,
                channelID = message.senderId + message.recipientId
            )
        )*/
    }

    override suspend fun getAllMessagesFromServer(
        senderID: String,
        recipient: User,
        onComplete: (chatMessages: List<TextMessage>) -> Unit,
        onNewMessage: (TextMessage) -> Unit
    ) {
        //server.getAllMessagesFromServer(senderID, recipient.userId, onComplete, onNewMessage)
        //server.getMessagesFromRealtimeDatabase(senderID, recipient.userId, onComplete, onNewMessage)
    }


    fun getPagerMessages(
        senderID: String,
        recipient: User,
        onComplete: (chatMessages: List<TextMessage>) -> Unit,
        onNewMessage: (TextMessage) -> Unit
    ): Flow<PagingData<TextMessage>>
    {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                maxSize = PAGE_SIZE + (PAGE_SIZE * 2),
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                MessagesPagingSource(
                    server,
                    senderID,
                    recipient.userId,
                )
            }
        ).flow
    }



    //Getting message from database
    /*override fun getMessagesInfo(channelId: String): LiveData<List<TextMessage>>{
        //val getMessages: LiveData<>
        return try {
            //val dataList = ArrayList<TextMessage>()
            val messages: LiveData<List<TextMessage>> = Transformations.map(messageDao.getAllMessagesInfo(channelId)){
                it.asTextMessageItem()
            }

            *//*for (i in messages) {
                Log.d("check", "Message text: ${i.messageText}") // # # #
            }*//*
            //dataList.addAll(messages.asTextMessageItem())
            //Log.d("check", "Message List: ${messages.asTextMessageItem().forEach { it.text }}")
            //Result.Success(dataList.toList())
            messages
        } catch (ex: java.lang.Exception) {
            Log.d("check", "error happened from database: ${ex.localizedMessage}")
            val messages: LiveData<List<TextMessage>> = Transformations.map(messageDao.getAllMessagesInfo(channelId)){
                it.asTextMessageItem()
            }

            messages
        }
    }*/


    //Adding the new single message to the server and database
    @OptIn(DelicateCoroutinesApi::class)
    //fun addNewMessageToServer(messageContent: TextMessage, senderID: String, recipientID: String) {
        //server.sendMessage(messageContent, senderID, recipientID)
        //Log.d("checkk", "This is last message from server: ${newMessage.value}")
        //Log.d("check", "message sent To Server: $messageSent"
    //}

    fun createChatChannelToServer(){

    }


    /**Getting the single message and adding it to the database*/
    /*fun getMessagesFromServer(channelID: String) {
        val query = chatChannelCollectionRef.document(channelID).collection("messages")
            .orderBy("date", Query.Direction.DESCENDING)


        query.addSnapshotListener { querySnapshot, _ ->
            //Clear Recycler view adapter
            messageList.clear()
            val querySnapShot = querySnapshot!!.documents[0]
            //Log.d("check", "documents size: ${querySnapshot!!.documents.size}")
            querySnapshot!!.documents.forEach { document ->

                if (querySnapShot["type"] == MessageType.TEXT) {

                    */
    /**Getting the single message object from database*//*
                    val textMessage = querySnapShot.toObject(TextMessage::class.java)!!
                    if (textMessage.senderId == mAuth.currentUser!!.uid) {
                        //messagesender = "sender"
                        *//*messageList.add(
                            TextMessage(
                                textMessage.text,
                                textMessage.senderId,
                                textMessage.recipientId,
                                textMessage.senderName,
                                textMessage.recipientName,
                                textMessage.date,
                                textMessage.type,
                                channelID
                            )
                        )*//*
                        Log.d("check", "message list from server: $messageList")
                        GlobalScope.launch(Dispatchers.IO) {
                            withContext(Dispatchers.IO) {
                                insertNewMessage(
                                    TextMessage(
                                        textMessage.text,
                                        textMessage.senderId,
                                        textMessage.recipientId,
                                        textMessage.senderName,
                                        textMessage.recipientName,
                                        textMessage.date,
                                        textMessage.type,
                                        channelID
                                    )
                                )
                            }
                        }
                        //_senderItem.value = SenderTextItem(messageList)
                    } else {
                        //messagesender = "receiver"
                        *//*messageList.add(
                            TextMessage(
                                textMessage.text,
                                textMessage.senderId,
                                textMessage.recipientId,
                                textMessage.senderName,
                                textMessage.recipientName,
                                textMessage.date,
                                textMessage.type,
                                channelID
                            )
                        )*//*

                        GlobalScope.launch(Dispatchers.IO) {
                            withContext(Dispatchers.IO) {
                                insertNewMessage(
                                    TextMessage(
                                        textMessage.text,
                                        textMessage.senderId,
                                        textMessage.recipientId,
                                        textMessage.senderName,
                                        textMessage.recipientName,
                                        textMessage.date,
                                        textMessage.type,
                                        channelID
                                    )
                                )
                            }
                        }
                        Log.d("check", "message list from server: $messageList")


                    }
                } else {
                    val imageMessage = querySnapShot.toObject(ImageMessage::class.java)!!
                    //messagesender = "sender"
                    if (imageMessage.senderId == mAuth.currentUser!!.uid) {
                        messageList.add(
                            ImageMessage(
                                imageMessage.imagePath,
                                imageMessage.senderId,
                                imageMessage.recipientId,
                                imageMessage.senderName,
                                imageMessage.recipientName,
                                imageMessage.date,
                                imageMessage.type,
                                channelID
                            )
                        )
                    } else {
                        //messagesender = "receiver"
                        messageList.add(
                            ImageMessage(
                                imageMessage.imagePath,
                                imageMessage.senderId,
                                imageMessage.recipientId,
                                imageMessage.senderName,
                                imageMessage.recipientName,
                                imageMessage.date,
                                imageMessage.type,
                                channelID
                            )
                        )
                    }
                }
            }


        }

    }*/


    //Getting current Auth user from database
    override suspend fun getCurrentUserInfo(): Result<AuthUserDTO> {
        return try {
            val user = currentUserDao.getCurrentUserInfo()
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
        } catch (ex: java.lang.Exception) {
            Result.Error(ex.localizedMessage)
        }
    }

    //Adding message to database
    override suspend fun addCurrentUserInfo(currentUser: AuthUserDTO) {
        currentUserDao.addCurrentUserInfo(currentUser)
    }

    //Deleting user once Logged out
    override suspend fun deleteCurrentUserInfo() {
        currentUserDao.deleteCurrentUserInfo()
    }


    fun getChatMessageFromServer(
        channelId: String, onComplete: (chatMessages: List<TextMessage>) -> Unit
    ) {

        val query = chatChannelCollectionRef.document(channelId).collection("messages")
            .orderBy("date", Query.Direction.DESCENDING)

        messageList.clear()
        query.addSnapshotListener { querySnapshot, _ ->
            //Clear Recycler view adapter

            val chatsMessages = arrayListOf<TextMessage>()
            querySnapshot!!.documents.forEach { document ->

                val textMessage = document.toObject(TextMessage::class.java)!!
                //Log.d("check", "text message is: " + textMessage.text)
                if (textMessage.senderId == mAuth.currentUser!!.uid) {
                    chatsMessages.add(
                        TextMessage(
                            id = "",
                            textMessage.text,
                            textMessage.senderId,
                            textMessage.recipientId,
                            textMessage.senderName,
                            textMessage.recipientName,
                            textMessage.date,
                            textMessage.type,
                            channelId
                        )
                    )
                } else {
                    chatsMessages.add(
                        TextMessage(
                            id = "",
                            textMessage.text,
                            textMessage.recipientId,
                            textMessage.senderId,
                            textMessage.senderName,
                            textMessage.recipientName,
                            textMessage.date,
                            textMessage.type,
                            channelId
                        )
                    )
                }
            }
            onComplete(chatsMessages)
            Log.d("check", "text messagesss is: $chatsMessages")
        }
    }

    /*fun getLastChats(uid: String): List<ChatItem>{
        return server.getChatsListFromServer(userId = uid)
    }*/

    override suspend fun insertAllMessagesToDB(chat: List<TextMessage>) {
        messageDao.addAllMessagesToDB(chat.asMessageDTO())
    }

}