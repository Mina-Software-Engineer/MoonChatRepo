package com.mina.moonchat.models

import java.util.*

data class ImageMessage(
    val imagePath: String,
    override val senderId: String,
    override val recipientId: String,
    override val senderName: String,
    override val recipientName: String,
    override val date: Date,
    override val type: String,
    override val channelId: String

):Message
