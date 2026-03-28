package com.mina.moonchat.models

data class ChatItem(
    val chatId: String,
    val username: String,
    val profileImg: String?,
    val onlineState: Boolean,
    val time: String,
    val lastMessage: String
)
