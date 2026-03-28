package com.mina.moonchat.data

import androidx.lifecycle.LiveData
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.data.dto.UserInfoDTO
import com.mina.moonchat.intro_activity.data.Result
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.models.User

interface UserDataSource {

    suspend fun getUsersInfo(): Result<List<UserInfoDTO>>
    suspend fun insertNewUser(user: UserInfoDTO)

    suspend fun getMessagesInfoFromDB(channelId: String): List<TextMessage>
    suspend fun getLastMessageInfoFromDB(channelId: String, recipientID: String): List<TextMessage>
    suspend fun insertNewMessageToDB(message: TextMessage)
    suspend fun insertAllMessagesToDB(messages: List<TextMessage>)

    suspend fun getCurrentUserInfo(): Result<AuthUserDTO>
    suspend fun addCurrentUserInfo(currentUser: AuthUserDTO)
    suspend fun deleteCurrentUserInfo()
    suspend fun getAllMessagesFromServer(senderID: String, recipient: User, onComplete: (List<TextMessage>) -> Unit, onNewMessage: (TextMessage) -> Unit)
}