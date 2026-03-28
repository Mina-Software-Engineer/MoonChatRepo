package com.mina.moonchat.data.dto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mina.moonchat.models.TextMessage
import java.util.*

/*
@Entity(tableName = "MessageEntity")
data class MessagesDTO(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "message_id")
    val messageId: Int = 0,
    @ColumnInfo(name = "message_text")
    val messageText: String,
    @ColumnInfo(name = "sender_id")
    var senderId: String,
    @ColumnInfo(name = "recipient_id")
    var recipientId: String,
    @ColumnInfo(name = "sender_name")
    var senderName: String,
    @ColumnInfo(name = "recipient_name")
    var recipientName: String,
    @ColumnInfo(name = "message_time")
    var messageTime: Long,
    @ColumnInfo(name = "msg_type")
    var messageType: String,
    @ColumnInfo(name = "channelID")
    var channelID: String
){
    constructor() : this(0, "", "", "", "", "", 0,"","")
}
*/


@Entity(
    tableName = "MessageEntity",
    indices = [
        Index(value = ["channelID", "message_time"]), // For faster pagination queries
        Index(value = ["message_id"], unique = true)   // Prevent duplicate messages
    ]
)
data class MessagesDTO(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "message_text")
    val messageText: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "recipient_id")
    val recipientId: String,

    @ColumnInfo(name = "message_time")
    val messageTime: Long,

    @ColumnInfo(name = "firebase_timestamp")
    val firebaseTimestamp: Long = 0, // Server timestamp for consistent ordering

    @ColumnInfo(name = "channelID")
    val channelID: String,

    @ColumnInfo(name = "msg_type")
    val messageType: String = "TEXT",

    @ColumnInfo(name = "sender_name")
    var senderName: String,

    @ColumnInfo(name = "recipient_name")
    var recipientName: String,

    @ColumnInfo(name = "status")
    val status: MessageStatus = MessageStatus.SENT, // Track message state

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false
)

enum class MessageStatus {
    PENDING,    // Message created locally, not yet sent to Firebase
    SENT,       // Message successfully sent to Firebase
    RECEIVED,   // Message received from other user
    FAILED,     // Message failed to send
    SYNCED      // Message confirmed synced with Firebase
}

// Improved mapper with all fields
fun MessagesDTO.toTextMessage(): TextMessage {
    return TextMessage(
        id = this.messageId,
        text = this.messageText,
        senderId = this.senderId,
        recipientId = this.recipientId,
        senderName = this.senderName,
        recipientName = this.recipientName,
        date = Date(this.messageTime),
        type = this.messageType,
        channelId = this.channelID,
        status = this.status.name,
        isRead = this.isRead
    )
}

fun TextMessage.toMessagesDTO(status: MessageStatus = MessageStatus.SENT): MessagesDTO {
    return MessagesDTO(
        messageId = this.id,
        messageText = this.text,
        senderId = this.senderId,
        recipientId = this.recipientId,
        senderName = this.senderName,
        recipientName = this.recipientName,
        messageTime = this.date.time,
        firebaseTimestamp = this.date.time, // Will be updated with server timestamp
        channelID = this.channelId,
        messageType = this.type,
        status = status,
        isRead = this.isRead
    )
}
