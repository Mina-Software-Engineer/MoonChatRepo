package com.mina.moonchat.models

import com.mina.moonchat.data.dto.MessageStatus
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.toMessagesDTO
import com.mina.moonchat.data.dto.toTextMessage
import com.google.firebase.database.PropertyName
import java.util.*

enum class MessageDeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    SEEN,
    FAILED
}

data class TextMessage (
    @get:PropertyName("id")
    @set:PropertyName("id")
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

    @get:PropertyName("isRead")
    val isRead: Boolean = false,

    @get:PropertyName("isDelivered")
    val isDelivered: Boolean = false
) : Message {
    constructor() : this("","", "", "", "", "", Date(),"","", "SENT", false, false)

    fun isSentBy(currentUserId: String): Boolean = senderId == currentUserId

    fun isPending(): Boolean = status == "PENDING"

    fun isFailed(): Boolean = status == "FAILED"

    fun resolveDeliveryState(): MessageDeliveryState {
        return when {
            isPending() -> MessageDeliveryState.PENDING
            isFailed() -> MessageDeliveryState.FAILED
            isRead -> MessageDeliveryState.SEEN
            isDelivered -> MessageDeliveryState.DELIVERED
            status.equals("PENDING", ignoreCase = true) -> MessageDeliveryState.PENDING
            status.equals("FAILED", ignoreCase = true) -> MessageDeliveryState.FAILED
            status.equals("RECEIVED", ignoreCase = true) -> MessageDeliveryState.DELIVERED
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