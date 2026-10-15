package com.mina.moonchat.models

import com.google.firebase.database.PropertyName
import com.google.firebase.database.Exclude
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.toMessagesDTO
import com.mina.moonchat.data.dto.toTextMessage
import java.util.Date

enum class MessageDeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    SEEN,
    FAILED
}

data class TextMessage(
    @get:PropertyName("id")
    @set:PropertyName("id")
    var id: String = "",

    @get:PropertyName("text")
    @set:PropertyName("text")
    var text: String = "",

    @get:PropertyName("senderId")
    @set:PropertyName("senderId")
    override var senderId: String = "",

    @get:PropertyName("recipientId")
    @set:PropertyName("recipientId")
    override var recipientId: String = "",

    @get:PropertyName("senderName")
    @set:PropertyName("senderName")
    override var senderName: String = "",

    @get:PropertyName("recipientName")
    @set:PropertyName("recipientName")
    override var recipientName: String = "",

    @get:Exclude
    @set:Exclude
    override var date: Date = Date(),

    @get:PropertyName("type")
    @set:PropertyName("type")
    override var type: String = MessageType.TEXT,

    @get:PropertyName("channelId")
    @set:PropertyName("channelId")
    override var channelId: String = "",

    @get:PropertyName("status")
    @set:PropertyName("status")
    var status: String = "SENT",

    @get:PropertyName("isRead")
    @set:PropertyName("isRead")
    var isRead: Boolean = false,

    @get:PropertyName("isDelivered")
    @set:PropertyName("isDelivered")
    var isDelivered: Boolean = false,

    @get:PropertyName("retryCount")
    @set:PropertyName("retryCount")
    var retryCount: Int = 0,

    @get:PropertyName("lastAttemptTime")
    @set:PropertyName("lastAttemptTime")
    var lastAttemptTime: Long = 0,

    @get:PropertyName("media")
    @set:PropertyName("media")
    var media: ChatImageMedia? = null,

    @get:Exclude
    @set:Exclude
    var localImagePath: String? = null,

    @get:Exclude
    @set:Exclude
    var localImageMimeType: String? = null,

    @get:Exclude
    @set:Exclude
    var localImageSize: Long? = null,

    @get:Exclude
    @set:Exclude
    var localImageWidth: Int? = null,

    @get:Exclude
    @set:Exclude
    var localImageHeight: Int? = null
) : Message {

    @PropertyName("date")
    fun getDateForFirebase(): Long {
        return date.time
    }

    @PropertyName("date")
    fun setDateFromFirebase(value: Any?) {
        date = when (value) {
            is Long -> Date(value)
            is Double -> Date(value.toLong())
            is Int -> Date(value.toLong())
            is Number -> Date(value.toLong())
            is String -> value.toLongOrNull()?.let { Date(it) } ?: Date()
            is Date -> value
            is Map<*, *> -> {
                val time = (value[".sv"] ?: value["time"] ?: value["timestamp"])
                when (time) {
                    is Long -> Date(time)
                    is Double -> Date(time.toLong())
                    is Number -> Date(time.toLong())
                    else -> Date()
                }
            }
            else -> Date()
        }
    }

    fun isSentBy(currentUserId: String): Boolean = senderId == currentUserId

    fun isPending(): Boolean = status.equals("PENDING", ignoreCase = true)

    fun isFailed(): Boolean = status.equals("FAILED", ignoreCase = true)

    fun isRetryable(): Boolean = isFailed() && !isDelivered && !isRead

    fun resolveDeliveryState(): MessageDeliveryState {
        return when {
            isPending() -> MessageDeliveryState.PENDING
            isFailed() -> MessageDeliveryState.FAILED
            isRead -> MessageDeliveryState.SEEN
            isDelivered -> MessageDeliveryState.DELIVERED
            else -> MessageDeliveryState.SENT
        }
    }

    fun deliveryStatusText(): String = when (resolveDeliveryState()) {
        MessageDeliveryState.PENDING -> "Pending"
        MessageDeliveryState.SENT -> "Sent"
        MessageDeliveryState.DELIVERED -> "Delivered"
        MessageDeliveryState.SEEN -> "Seen"
        MessageDeliveryState.FAILED -> "Failed"
    }

    fun deliveryStatusIndicator(): String = when (resolveDeliveryState()) {
        MessageDeliveryState.PENDING -> "..."
        MessageDeliveryState.SENT -> "✓"
        MessageDeliveryState.DELIVERED -> "✓✓"
        MessageDeliveryState.SEEN -> "✓✓✓"
        MessageDeliveryState.FAILED -> "!"
    }
}

// Extension functions for list conversion
fun List<MessagesDTO>.asTextMessageItem(): List<TextMessage> {
    return map { it.toTextMessage() }
}

fun List<TextMessage>.asMessageDTO(): List<MessagesDTO> {
    return map { it.toMessagesDTO() }
}
