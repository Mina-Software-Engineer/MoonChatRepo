package com.mina.moonchat.models

import com.mina.moonchat.utils.ChatDateLabel

sealed class ChatTimelineItem {
    data class MessageItem(val message: TextMessage) : ChatTimelineItem()
    data class DateSeparator(val dayKey: String, val label: ChatDateLabel) : ChatTimelineItem()
}
