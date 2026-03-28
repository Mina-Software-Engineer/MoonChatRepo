package com.mina.moonchat.models

import java.util.*

interface Message {
    val senderId: String
    val recipientId: String
    val senderName: String
    val recipientName: String
    val date: Date
    val type: String
    val channelId: String
}