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
    val isRead: Boolean = false,

    @ColumnInfo(name = "is_delivered")
    val isDelivered: Boolean = false,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_attempt_time")
    val lastAttemptTime: Long = 0,

    @ColumnInfo(name = "media_json")
    val mediaJson: String? = null,

    @ColumnInfo(name = "local_image_path")
    val localImagePath: String? = null,

    @ColumnInfo(name = "local_image_mime_type")
    val localImageMimeType: String? = null,

    @ColumnInfo(name = "local_image_size")
    val localImageSize: Long? = null,

    @ColumnInfo(name = "local_image_width")
    val localImageWidth: Int? = null,

    @ColumnInfo(name = "local_image_height")
    val localImageHeight: Int? = null
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
        isRead = this.isRead,
        isDelivered = this.isDelivered,
        retryCount = this.retryCount,
        lastAttemptTime = this.lastAttemptTime,
        media = this.mediaJson?.let { com.mina.moonchat.models.ChatImageMedia.fromJson(it) },
        localImagePath = this.localImagePath,
        localImageMimeType = this.localImageMimeType,
        localImageSize = this.localImageSize,
        localImageWidth = this.localImageWidth,
        localImageHeight = this.localImageHeight
    )
}

fun TextMessage.toMessagesDTO(defaultStatus: MessageStatus = MessageStatus.SENT): MessagesDTO {
    val messageStatus = try {
        if (this.status.isNotBlank()) MessageStatus.valueOf(this.status) else defaultStatus
    } catch (_: Exception) {
        defaultStatus
    }
    return MessagesDTO(
        messageId = this.id,
        messageText = this.text,
        senderId = this.senderId,
        recipientId = this.recipientId,
        senderName = this.senderName,
        recipientName = this.recipientName,
        messageTime = this.date.time,
        firebaseTimestamp = if (this.date.time > 0) this.date.time else System.currentTimeMillis(),
        channelID = this.channelId,
        messageType = this.type,
        status = messageStatus,
        isRead = this.isRead,
        isDelivered = this.isDelivered,
        retryCount = this.retryCount,
        lastAttemptTime = this.lastAttemptTime,
        mediaJson = this.media?.toJson(),
        localImagePath = this.localImagePath,
        localImageMimeType = this.localImageMimeType,
        localImageSize = this.localImageSize,
        localImageWidth = this.localImageWidth,
        localImageHeight = this.localImageHeight
    )
}
