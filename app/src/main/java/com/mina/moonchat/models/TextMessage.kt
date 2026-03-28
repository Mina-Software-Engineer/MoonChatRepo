package com.mina.moonchat.models

import androidx.room.TypeConverter
import com.mina.moonchat.data.dto.MessageStatus
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.toMessagesDTO
import com.mina.moonchat.data.dto.toTextMessage
import java.util.*

data class TextMessage (
    var id: String = "",
    val text: String,
    override val senderId: String,
    override val recipientId: String,
    override val senderName: String,
    override val recipientName: String,
    override val date: Date,
    override val type: String = MessageType.TEXT,
    override val channelId: String,
    val status: String = "SENT", // PENDING, SENT, RECEIVED, FAILED, SYNCED
    val isRead: Boolean = false
) : Message {
    constructor() : this("","", "", "", "", "", Date(),"","", "SENT", false)

    /**
     * Check if this message was sent by the current user
     */
    fun isSentBy(currentUserId: String): Boolean = senderId == currentUserId

    /**
     * Check if message is pending (not yet synced with Firebase)
     */
    fun isPending(): Boolean = status == "PENDING"

    /**
     * Check if message failed to send
     */
    fun isFailed(): Boolean = status == "FAILED"
}

// Extension functions for list conversion
fun List<MessagesDTO>.asTextMessageItem(): List<TextMessage> {
    return map { it.toTextMessage() }
}

fun List<TextMessage>.asMessageDTO(): List<MessagesDTO> {
    return map { it.toMessagesDTO() }
}

// Type converters for Date
@TypeConverter
fun dateToLong(date: Date): Long = date.time

@TypeConverter
fun longToDate(value: Long): Date = Date(value)

// Type converter for MessageStatus
@TypeConverter
fun messageStatusToString(status: MessageStatus): String = status.name

@TypeConverter
fun stringToMessageStatus(value: String): MessageStatus =
    try {
        MessageStatus.valueOf(value)
    } catch (e: IllegalArgumentException) {
        MessageStatus.SENT
    }
