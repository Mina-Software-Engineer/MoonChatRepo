package com.mina.moonchat.models

data class ChatItem(
    val chatId: String,
    val recipientId: String,
    val username: String,
    val profileImg: String?,
    val onlineState: Boolean,
    val time: String,
    val lastMessage: String,
    val hasUnreadIncoming: Boolean = false,
    val unreadCount: Int = 0,
    val isTyping: Boolean = false,
    val lastMessageStatus: String = ""
)